(ns tests.all
  (:require [cljs.core.async :refer [<! >! chan timeout]]
            [cljs-web3-next.core :as web3-core]
            [cljs-web3-next.db :as web3-db]
            [cljs-web3-next.eth :as web3-eth]
            [cljs-web3-next.evm :as web3-evm]
            [cljs-web3-next.net :as web3-net]
            [cljs-web3-next.personal :as web3-personal]
            [cljs-web3-next.shh :as web3-shh]
            [cljs.test :refer-macros [deftest is testing run-tests use-fixtures async]]
            [cljsjs.web3]
            [day8.re-frame.test :refer [run-test-async wait-for run-test-sync]]
            [district0x.re-frame.web3-fx]
            [tests.contract-helpers :as test-contract-helpers]
            [re-frame.core :refer [reg-event-fx console dispatch trim-v reg-sub subscribe]]
            ["web3" :as Web3])
  (:require-macros [cljs.core.async.macros :refer [go]]))

; (def web3 (web3/create-web3 "ws://d0x-vm:8549"))
(def web3
  (new Web3 (web3-core/ws-provider "ws://d0x-vm:8549")))

(def gas-limit 4500000)

(def interceptors [trim-v])

(defn contract [db]
  (:contract db))

(defn accounts [db]
  (:accounts db))

(defn balance [db address]
  (get-in db [:balances address]))

(defn total-supply [db]
  (:total-supply db))

(defn token-balance [db address]
  (get-in db [:token-balances address]))

(reg-sub
  ::contract
  contract)

(reg-sub
  ::accounts
  accounts)

(reg-sub
  ::total-supply
  total-supply)

(reg-sub
  ::mint-event-args
  (fn [db]
    (:mint-event-args db)))

(reg-sub
  ::token-balance
  (fn [db [_ & args]]
    (apply token-balance db args)))

(reg-sub
  ::balance
  (fn [db [_ & args]]
    (apply balance db args)))

(reg-event-fx
  ::error
  interceptors
  (fn []
    ))

(reg-event-fx
  ::load-accounts
  interceptors
  (fn [_ []]
    {:web3/call {:web3 web3
                 :fns [{:fn web3-eth/accounts
                        :on-success [::accounts-loaded]
                        :on-error [::error]}]}}))


(reg-event-fx
  ::accounts-loaded
  interceptors
  (fn [{:keys [:db]} [accounts]]
    {:db (assoc db :accounts accounts)}))


(reg-event-fx
  ::deploy-contract
  interceptors
  (fn [_ [abi tx-opts]]
    {:web3/call {:web3 web3
                 :fns [{:fn web3-eth/contract-new
                        :args [abi tx-opts]
                        :on-success [::contract-deployed*]
                        :on-error [::error]}]}}))


(reg-event-fx
  ::contract-deployed*
  interceptors
  (fn [{:keys [:db]} [contract-instance]]
    (when (aget contract-instance "address")                ;; Contract gets address only on 2nd fire
      {:dispatch [::contract-deployed contract-instance]})))


(reg-event-fx
  ::contract-deployed
  interceptors
  (fn [{:keys [:db]} [contract-instance]]
    {:db (assoc db :contract contract-instance)}))

(reg-event-fx
  ::get-token-total-supply
  interceptors
  (fn [{:keys [:db]}]
    {:web3/call {:web3 web3
                 :fns [{:instance (contract db)
                        :fn :total-supply
                        :on-success [::token-total-supply-result]
                        :on-error [::error]}]}}))


(reg-event-fx
  ::token-total-supply-result
  interceptors
  (fn [{:keys [:db]} [result]]
    {:db (assoc db :total-supply (.toNumber result))}))


(reg-event-fx
  ::mint-token
  interceptors
  (fn [{:keys [:db]} [{:keys [:to :amount :from]}]]
    {:web3/call {:web3 web3
                 :fns [{:instance (contract db)
                        :fn :mint
                        :args [to amount]
                        :tx-opts {:from from
                                  :gas gas-limit}
                        :on-tx-hash [::mint-tx-hash-loaded]
                        :on-tx-success [::token-minted]
                        :on-tx-hash-error [::error]
                        :on-tx-error [::error]
                        :on-tx-receipt [::mint-token-receipt-loaded]}]}}))

(reg-event-fx
  ::mint-tx-hash-loaded
  interceptors
  (fn [{:keys [:db]}]
    {}))

(reg-event-fx
  ::token-minted
  interceptors
  (fn [{:keys [:db]} [result]]
    {}))

(reg-event-fx
  ::mint-token-receipt-loaded
  interceptors
  (fn [{:keys [:db]}]
    ))


(reg-event-fx
  ::watch-mint
  interceptors
  (fn [{:keys [:db]} [{:keys [:to]}]]
    {:web3/watch-events {:events [{:id :mint-watcher
                                   :event :Mint
                                   :instance (contract db)
                                   :block-filter-opts {:from-block 0 :to-block "latest"}
                                   :event-filter-opts {:to to}
                                   :on-success [::token-minted-event]
                                   :on-error [::error]}]}}))


(reg-event-fx
  ::token-minted-event
  interceptors
  (fn [{:keys [:db]} [event-args]]
    {:db (assoc db :mint-event-args event-args)}))


(reg-event-fx
  ::load-token-balances
  interceptors
  (fn [{:keys [:db]} [addresses]]
    {:web3/get-balances {:web3 web3
                         :addresses (for [address addresses]
                                      {:id :token-balances
                                       :address address
                                       :instance (contract db)
                                       :watch? true
                                       :on-success [::token-balance-loaded address]
                                       :on-error [::error]})}}))


(reg-event-fx
  ::token-balance-loaded
  interceptors
  (fn [{:keys [:db]} [address balance]]
    {:db (assoc-in db [:token-balances address] (str balance))}))


(reg-event-fx
  ::transfer-token
  interceptors
  (fn [{:keys [:db]} [{:keys [:from :to :amount]}]]
    {:web3/call {:web3 web3
                 :fns [{:instance (contract db)
                        :fn :transfer
                        :args [to amount]
                        :tx-opts {:from from
                                  :gas gas-limit}
                        :on-tx-success [::token-transferred]
                        :on-tx-hash-error [::error]
                        :on-tx-error [::error]}]}}))

(reg-event-fx
  ::token-transferred
  interceptors
  (fn [{:keys [:db]}]
    {}))

(reg-event-fx
  ::load-ether-balances
  interceptors
  (fn [{:keys [:db]} [addresses]]
    {:web3/get-balances {:web3 web3
                         :addresses (for [address addresses]
                                      {:id :token-balances
                                       :address address
                                       :watch? true
                                       :on-success [::ether-balance-loaded address]
                                       :on-error [::error]})}}))


(reg-event-fx
  ::ether-balance-loaded
  interceptors
  (fn [{:keys [:db]} [address balance]]
    {:db (assoc-in db [:balances address] (str balance))}))


(reg-event-fx
  ::transfer-ether
  interceptors
  (fn [{:keys [:db]} [{:keys [:from :to :amount]}]]
    {:web3/call {:web3 web3
                 :fns [{:fn web3-eth/send-transaction!
                        :args [{:from from :to to :value amount}]
                        :on-success [::transfer-ether-tx-hash]
                        :on-error [::error]}]}}))

(reg-event-fx
  ::transfer-ether-tx-hash
  interceptors
  (fn [{:keys [:db]} [tx-hash]]
    {:web3/watch-transactions {:web3 web3
                               :transactions [{:tx-hash tx-hash
                                               :on-tx-success [::ether-transferred]
                                               :on-tx-error [::error]}]}}))

(reg-event-fx
  ::ether-transferred
  interceptors
  (fn [{:keys [:db]}]
    ))

(reg-event-fx
  ::stop-watching-all
  interceptors
  (fn [{:keys [:db]} []]
    {:web3/stop-watching-all true}))

(use-fixtures
  :each
  {:before (fn [])
   :after (fn [])})


(deftest sync-sanity
  (is (= 1 1)))

(deftest async-sanity
  (async done
         (go
           (let [wait-ms 1100
                 start-timestamp (.now js/Date)
                 _ (<! (timeout wait-ms))
                 end-timestamp (.now js/Date)]
             (is (<= wait-ms (- end-timestamp start-timestamp)))
             (done)))))

(def tests-done (atom {
                       :contract-tests false
                       ; :ether-tests false
                       }))

#_ (deftest contract-tests
  (run-test-async
    (let [contract (subscribe [::contract])
          accounts (subscribe [::accounts])
          total-supply (subscribe [::total-supply])
          mint-event-args (subscribe [::mint-event-args])]
      (dispatch [::load-accounts])

      (wait-for [::accounts-loaded ::error]
        (is (not (empty? @accounts)))

        (dispatch [::deploy-contract test-contract-helpers/contract-abi
                   {:data test-contract-helpers/contract-bin
                    :gas gas-limit
                    :from (first @accounts)}])

        (wait-for [::contract-deployed ::error]
          (is (string? (aget @contract "address")))
          (is (= 4 3))

          (dispatch [::get-token-total-supply])

          (wait-for [::token-total-supply-result ::error]
            (is (= 0 @total-supply))

            (dispatch [::mint-token {:from (first @accounts)
                                     :to (second @accounts)
                                     :amount (web3-core/to-wei 10 :ether)}])
            (wait-for [[::mint-tx-hash-loaded ::mint-token-receipt-loaded ::token-minted] :error]
              (dispatch [::watch-mint {:to (second @accounts)}])

              (wait-for [::token-minted-event ::error]
                (is (= {:to (second @accounts)
                        :amount (web3-core/to-wei 10 :ether)}
                       (update @mint-event-args :amount str)))

                (dispatch [::load-token-balances [(second @accounts)]])

                (wait-for [::token-balance-loaded ::error]
                  (= @(subscribe [::token-balance (second @accounts)])
                     (web3-core/to-wei 10 :ether))

                  (testing "Watching token balance works"
                    (dispatch [::transfer-token {:from (second @accounts)
                                                 :to (last @accounts)
                                                 :amount (web3-core/to-wei 4 :ether)}])

                    (wait-for [::token-balance-loaded ::error]
                      (dispatch [::stop-watching-all])
                      (= @(subscribe [::token-balance (second @accounts)])
                         (web3-core/to-wei 6 :ether))
                      (swap! tests-done assoc :contract-tests true)
                      )))))))))))

#_ (deftest ether-tests
  (run-test-async
    (let [accounts (subscribe [::accounts])]
      (dispatch [::load-accounts])

      (wait-for [::accounts-loaded ::error]
        (is (not (empty? @accounts)))

        (dispatch [::transfer-ether {:from (first @accounts)
                                     :to (second @accounts)
                                     :amount (web3-core/to-wei 200 :ether)}])

        (testing "Expect error because user doesn't have that much ether"
          (wait-for [::error ::ether-transferred]

            (dispatch [::transfer-ether {:from (first @accounts)
                                         :to (second @accounts)
                                         :amount (web3-core/to-wei 1 :ether)}])

            (wait-for [::ether-transferred ::error]
              (dispatch [::load-ether-balances [(second @accounts)]])

              (wait-for [::ether-balance-loaded ::error]
                (is (< 100 (web3-core/from-wei @(subscribe [::balance (second @accounts)]) :ether)))))))))))
