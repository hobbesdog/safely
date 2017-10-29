(ns safely.circuit-breaker
  (:require [safely.thread-pool :refer
             [fixed-thread-pool async-execute-with-pool]]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.tools.logging :as log]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;          ---==| C I R C U I T - B R E A K E R   S T A T S |==----          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- update-samples
  ;;TODO: doc
  [stats timestamp [_ fail error] {:keys [sample-size]}]
  ;; don't add requests which didn't enter the c.b.
  (if (= fail :circuit-open)
    stats
    (update stats
            :samples
            (fnil conj (ring-buffer sample-size))
            {:timestamp timestamp
             :failure   fail
             :error     error})))



(defn- update-counters
  ;;TODO: doc
  [stats timestamp [ok fail] {:keys [sample-size counters-buckets]}]
  (update stats
          :counters
          (fn [counters]
            (let [ts     (quot timestamp 1000)
                  min-ts (- ts counters-buckets)]
              (as-> (or counters (sorted-map)) $
                (update $ ts
                        (fn [{:keys
                             [success error timeout rejected open] :as p-counters}]
                          (let [p-counters (or p-counters
                                              {:success  0, :error  0, :timeout  0,
                                               :rejected 0, :open   0})]
                            (case fail

                              nil
                              (update p-counters :success inc)

                              :error
                              (update p-counters :error inc)

                              :timeout
                              (update p-counters :timeout inc)

                              :queue-full
                              (update p-counters :rejected inc)

                              :circuit-open
                              (update p-counters :open inc)))))

                ;; keep only last `counters-buckets` entries
                (apply dissoc $ (filter #(< % min-ts) (map first $))))))))



(defn- update-stats
  [stats result opts]
  (let [ts (System/currentTimeMillis)]
    (-> stats
        (update-samples  ts result opts)
        (update-counters ts result opts)
        (update :in-flight (fnil dec 0)))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                          ---==| P O O L S |==----                          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(comment
  ;; cb stats
  {:cb-name1 (atom
              {:counters {1509199799 {:success 0, :error 1, :timeout 0, :rejected 0, :open 0}},
               :samples [{:timestamp 1, :failure nil :error nil}
                         {:timestamp 2, :failure :timeout :error nil}]
               })}
  )
(def cb-stats (atom {}))
(def cb-pools (atom {}))



(defn pool
  "It returns a circuit breaker pool with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
  [{:keys [circuit-breaker thread-pool-size queue-size]}]
  (if-let [p (get @cb-pools (keyword circuit-breaker))]
    p
    (-> cb-pools
        (swap!
         update (keyword circuit-breaker)
         (fn [thread-pool]
           ;; might be already set by another
           ;; concurrent thread.
           (or thread-pool
              ;; if it doesn't exists then create one and initialize it.
              (fixed-thread-pool
               (str "safely.cb." (name circuit-breaker))
               thread-pool-size :queue-size queue-size))))
        (get (keyword circuit-breaker)))))



(defn- circuit-breaker-stats
  "It returns a circuit breaker stats atom with the key
   `circuit-breaker` if it exists, if not it creates one
   and initializes it."
  [{:keys [circuit-breaker sample-size] :as options}]
  (if-let [s (get @cb-stats (keyword circuit-breaker))]
    s
    (-> cb-stats
        (swap!
         update (keyword circuit-breaker)
         (fn [stats]
           ;; might be already set by another
           ;; concurrent thread.
           (or stats
              ;; if it doesn't exists then create one and initialize it.
              (atom
               {:status :closed :in-flight 0
                :samples (ring-buffer sample-size) :counters {}}))))
        (get (keyword circuit-breaker)))))




(comment
  (defn execute-with-circuit-breaker
    [f {:keys [circuit-breaker timeout sample-size] :as options}]
    (let [tp     (pool options) ;; retrieve or create thread-pool
          value  (async-execute-with-pool tp f)
          _      (swap! cb-stats update :in-flight (fnil inc 0))
          [_ fail error :as  result] (deref value timeout [nil :timeout nil])]
      ;; update stats
      (swap! cb-stats
             (fn [sts]
               (-> sts
                   (update-in [:cbs (keyword circuit-breaker)]
                              update-stats result options)
                   (update :in-flight (fnil dec 0)))))
      result))

  (defn execute-with-circuit-breaker
    [f {:keys [circuit-breaker timeout] :as options}]
    (if (should-allow-request? cb-stats)
      (let [;; retrieve or create thread-pool
            tp     (pool options)
            ;; executed in thread-pool and get a promise of result
            value  (async-execute-with-pool tp f)
            ;; wait result or timeout to expire
            [_ fail error :as  result] (deref value timeout [nil :timeout nil])]
        ;; update stats
        (swap! stats-atom update-stats result options)
        ;; return result
        result)
      )
    )
  )





(defn- should-allow-1-request?
  [stats-atom {:keys [circuit-breaker circuit-closed?] :as options}]
  (as-> stats-atom $
    (swap! $ (fn [stats]
               (let [closed? (circuit-closed? stats)]
                 (as-> stats $
                   ;; update status
                   (update $ :status (fn [os] (if closed? :closed :open)))
                   ;; if circuit is closed then increment the number of
                   ;; in flight requests.
                   (if closed?
                     (update $ :in-flight (fnil inc 0))
                     $)))))
    (= :closed (:status $))))



(defn execute-with-circuit-breaker
  [f {:keys [circuit-breaker timeout] :as options}]
  (let [stats (circuit-breaker-stats options)
        result
        ;;  check if circuit is open or closed.
        (if (should-allow-1-request? stats options)
          ;; retrieve or create thread-pool
          (-> (pool options)
              ;; executed in thread-pool and get a promise of result
              (async-execute-with-pool f)
              ;; wait result or timeout to expire
              (deref timeout [nil :timeout nil]))

          ;; ELSE
          [nil :circuit-open nil])]
    ;; update stats
    (swap! stats update-stats result options)
    ;; return result
    result))



(comment

  ;; TODO: add function to evaluate samples
  ;;       and open/close circuit
  ;; TODO: refactor metrics
  ;; TODO: add documentation
  ;; TODO: cancel timed out tasks.

  (def p (pool {:circuit-breaker :safely.test
                :queue-size 5 :thread-pool-size 5
                :sample-size 20 :counters-buckets 10}))

  cb-pools


  (def f (fn []
           (println "long running job")
           (Thread/sleep (rand-int 3000))
           (if (< (rand) 1/3)
             (throw (ex-info "boom" {}))
             (rand-int 1000))))


  (execute-with-circuit-breaker
   f
   {:circuit-breaker :safely.test
    :thread-pool-size 10
    :queue-size       5
    :sample-size      100
    :timeout          3000
    :counters-buckets 10
    :circuit-closed?  (constantly true)})

  (as-> @cb-stats $
    (:safely.test $)
    (deref $)
    (:counters $)
    )


  (->> @cb-stats
       first
       second
       deref
       :samples
       (map (fn [x] (dissoc x :error))))

  (keys @cb-stats)

  )