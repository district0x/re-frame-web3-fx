(defproject madvas.re-frame/web3-fx "0.1.6"
  :description "A re-frame effects handler for performing Ethereum Web3 API tasks"
  :url "https://github.com/madvas/re-frame-web3-fx"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojurescript "1.9.227"]
                 [cljs-web3 "0.19.0-0"]
                 [re-frame "0.8.0"]]

  :plugins [[lein-cljsbuild "1.1.4"]]

  :figwheel {:server-port 6963}

  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  :resource-paths []

  :profiles {:dev
             {:dependencies [[org.clojure/clojure "1.8.0"]
                             [binaryage/devtools "0.8.1"]
                             [com.cemerick/piggieback "0.2.1"]
                             [figwheel-sidecar "0.5.8"]
                             [org.clojure/tools.nrepl "0.2.11"]]
              :plugins [[lein-figwheel "0.5.8"]]
              :source-paths ["env/dev"]
              :resource-paths ["resources"]
              :cljsbuild {:builds [{:id "dev"
                                    :source-paths ["src" "test"]
                                    :figwheel {:on-jsload madvas.re-frame.test-runner/run}
                                    :compiler {:main madvas.re-frame.test-runner
                                               :output-to "resources/public/js/compiled/app.js"
                                               :output-dir "resources/public/js/compiled/out"
                                               :asset-path "/js/compiled/out"
                                               :source-map-timestamp true
                                               :optimizations :none
                                               :preloads [print.foo.preloads.devtools]
                                               }}]}}}

  )
