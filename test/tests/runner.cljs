(ns tests.runner
  (:require
    [tests.all]
    [cljs.core.async :as async]))

(enable-console-print!)

(defn start []
  (cljs.test/run-all-tests)
  )

(defn stop [done]
  ; (async/go-loop [period 1000]
  ;                (if (every? true? (vals @tests.all/tests-done))
  ;                  (do
  ;                    (println "DONE")
  ;                    (done))
  ;                  (do
  ;                    (println "WAITING")
  ;                    (async/<! (async/timeout period))
  ;                    (recur done))))
  (done))

(defn ^:export init []
  (start))

