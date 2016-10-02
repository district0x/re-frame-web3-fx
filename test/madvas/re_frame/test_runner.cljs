(ns madvas.re-frame.test-runner
  (:require [print.foo :include-macros true]
            [cljs.test :refer-macros [run-tests]]
            [madvas.re-frame.tests]))

(defn ^:export run []
  (.clear js/console)
  (run-tests 'madvas.re-frame.tests))