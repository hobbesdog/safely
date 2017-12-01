(ns safely.test-utils
  (:require [safely.circuit-breaker :refer [cb-pools cb-stats]]
            safely.core)
  (:import clojure.lang.ExceptionInfo
           java.util.concurrent.ThreadPoolExecutor))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                     ---==| T E S T   U T I L S |==----                     ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *counter* nil)



(defn count-passes []
  (swap! *counter* inc))



(defn boom []
  (throw (ex-info "BOOOOM!" {:cause :boom})))



(defmacro count-retry [body]
  (let [body# `(~(first body) (count-passes) ~@(next body))]
    `(binding [*counter* (atom 0)
               safely.core/*sleepless-mode* true]
       [~body#
        @*counter*])))



(defmacro sleepless [& body]
  `(binding [safely.core/*sleepless-mode* true]
     ~@body))



(defn crash-boom-bang!
  "a utility function which calls the first function in fs
  the first time is called, it calls the second function
  the second time is called and so on. It throws an Exception
  if no more functions are available to fs in a given call."
  [& fs]

  (let [xfs (atom fs)]
    (fn [& args]
      (let [f (or (first @xfs) (fn [&x] (throw (ex-info "No more functions available to call" {:cause :no-more-fn}))))
            _ (swap! xfs next)]
        (apply f args)))))



(defn uuid []
  (str (java.util.UUID/randomUUID)))



(defmacro run-thread [& body]
  `(let [result# (promise)]
     (.start
      (Thread.
       (fn []
         (try
           (deliver result# (do ~@body))
           (catch Throwable x#
             (deliver result# x#))))))
     result#))


(comment
  ;; not working as expected.
  (defmacro with-test-pools
    [& body]
    `(with-redefs
       [cb-stats (atom {})
        cb-pools (atom {})]
       (try
         ~@body
         (finally
           ;; shutdown all threads
           (->> @cb-pools
                (run! (fn [[k# ^ThreadPoolExecutor tp#]]
                        (println "shutting down pool:" k#)
                        (.shutdownNow tp#)))))))))



(defmacro with-parallel
  [n & body]
  `(let [simplify-errors# (fn [e#]
                            (cond
                              (not (instance? ExceptionInfo e#)) e#
                              (nil? (:cause (ex-data (.getCause ^ExceptionInfo e#)))) e#
                              :else (:cause (ex-data (.getCause ^ExceptionInfo e#)))))
         semaphore# (promise)
         results# (->> (range ~n)
                       (map
                        (fn [_#]
                          (run-thread
                           @semaphore#
                           ~@body)))
                       (doall))]
     (deliver semaphore# :ok)
     (map (comp simplify-errors# deref) results#)))
