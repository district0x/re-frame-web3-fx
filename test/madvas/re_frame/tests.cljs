(ns madvas.re-frame.tests
  (:require [cljs.core.async :refer [<! >! chan]]
            [cljs-web3.core :as web3]
            [cljs-web3.db :as web3-db]
            [cljs-web3.eth :as web3-eth]
            [cljs-web3.net :as web3-net]
            [cljs-web3.personal :as web3-personal]
            [cljs-web3.shh :as web3-shh]
            [cljs.test :refer-macros [deftest is testing run-tests use-fixtures async]]
            [cljsjs.web3]
            [madvas.re-frame.web3-fx]
            [re-frame.core :refer [reg-event-fx console dispatch]]
            )
  (:require-macros [cljs.core.async.macros :refer [go]]))

;(enable-console-print!)

(def w3 (web3/create-web3 "http://localhost:8549/"))
(def gas-limit 4500000)
(def ^:dynamic *contract* nil)
(def contract-source
  "
  pragma solidity ^0.4.6;

  contract test {

    event onBChanged(uint num);

    uint public b;
    uint public c;

    function test() {
      b = 5;
    }

    function multiply(uint a) constant returns(uint) {
      return a * b;
    }

    function setB(uint _b) {
      b = _b;
    }
    
    function setC(uint _c) {
      c = _c;
      onBChanged(b);
    }
  }")



(reg-event-fx
  :initialize
  (fn []
    {:db {}}))

(reg-event-fx
  :blockchain-contract-create
  (fn [_ [_ abi args contract-ch]]
    {:web3-fx.blockchain/fns
     {:web3 w3
      :fns [[web3-eth/contract-new abi args
             [:blockchain-contract-created contract-ch]
             :blockchain-contract-create-error]]}}))

(reg-event-fx
  :blockchain-contract-created
  (fn [_ [_ contract-ch Contract]]
    (go (>! contract-ch Contract))
    {}))

(reg-event-fx
  :blockchain-balances
  (fn [_ [_ balance-ch]]
    {:web3-fx.blockchain/balances
     {:web3 w3
      :addresses [(second (web3-eth/accounts w3))]
      :watch? true
      :blockchain-filter-opts "latest"
      :db-path [:balances]
      :dispatches [[:balance-loaded balance-ch] :balance-load-error]}}))

(reg-event-fx
  :balance-loaded
  (fn [_ [_ balance-ch balance]]
    (go (>! balance-ch balance))
    {}))

(reg-event-fx
  :blockchain-fns
  (fn [_ [_ coinbase-ch]]
    {:web3-fx.blockchain/fns
     {:web3 w3
      :fns [[web3-eth/coinbase [:coinbase-loaded coinbase-ch] :coinbase-load-error]]}}))

(reg-event-fx
  :coinbase-loaded
  (fn [_ [_ coinbase-ch coinbase]]
    (go (>! coinbase-ch coinbase))
    {}))

(reg-event-fx
  :blockchain-filter
  (fn [_ [_ filter-ch]]
    {:web3-fx.blockchain/filter
     {:web3 w3
      :db-path [:blockchain-filter]
      :blockchain-filter-opts "latest"
      :dispatches [[:block-loaded filter-ch] :block-load-error]}}))

(reg-event-fx
  :blockchain-filter-stop-watching
  (fn []
    {:web3-fx.blockchain/filter-stop-watching [:blockchain-filter]}))

(reg-event-fx
  :block-loaded
  (fn [_ [_ bloch-ch block]]
    (go (>! bloch-ch block))
    {}))

(reg-event-fx
  :contract-constant-fns
  (fn [_ [_ contract result-ch]]
    {:web3-fx.contract/constant-fns
     {:fns [[contract :multiply 9 [:multiply-loaded result-ch] :multiply-load-error]
            ]}}))

(reg-event-fx
  :multiply-loaded
  (fn [_ [_ result-ch result]]
    (go (>! result-ch result))
    {}))

(reg-event-fx
  :contract-state-fns
  (fn [_ [_ contract result-ch1 result-ch2]]
    {:web3-fx.contract/state-fns
     {:web3 w3
      :db-path [:contract-state-fns]
      :fns [[contract
             :set-b 15
             {:gas gas-limit
              :from (second (web3-eth/accounts w3))}
             [:set-b-sent result-ch1]
             :set-b-send-error
             [:set-b-receipt-loaded result-ch1]]
            nil
            [contract
             :set-c 42
             {:gas gas-limit
              :from (second (web3-eth/accounts w3))}
             [:set-c-sent result-ch2]
             :set-c-send-error
             [:set-c-receipt-loaded result-ch2]]]}}))

(reg-event-fx
  :set-b-sent
  (fn [_ [_ result-ch result]]
    (go (>! result-ch result))
    {}))

(reg-event-fx
  :set-b-receipt-loaded
  (fn [_ [_ result-ch result]]
    (go (>! result-ch result))
    {}))

(reg-event-fx
  :set-c-sent
  (fn [_ [_ result-ch result]]
    (go (>! result-ch result))
    {}))

(reg-event-fx
  :set-c-receipt-loaded
  (fn [_ [_ result-ch result]]
    (go (>! result-ch result))
    {}))

(reg-event-fx
  :contract-events
  (fn [{:keys [db]} [_ contract result-ch]]
    {:web3-fx.contract/events
     {:db db
      :db-path [:contract-events]
      :events [[contract :on-b-changed {} "latest" [:on-b-changed result-ch] :on-b-changed-error]]}}))

(reg-event-fx
  :on-b-changed
  (fn [_ [_ result-ch result]]
    (go (>! result-ch result))
    {}))

(reg-event-fx
  :contract-events-stop-watching
  (fn [{:keys [db]} _]
    {:web3-fx.contract/events-stop-watching
     {:db db
      :db-path [:contract-events]
      :event-ids [:on-b-changed]}}))

(deftest basic
  (is (web3/connected? w3))
  (is (seq (web3-eth/accounts w3)))
  ;(is (web3-personal/unlock-account w3 (second (web3-eth/accounts w3)) "m" 999999))
  (let [create-contract-ch (chan)
        balance-ch (chan)
        coinbase-ch (chan)
        block-ch (chan)
        constant-fn-ch (chan)
        state-fn-ch1 (chan)
        state-fn-ch2 (chan)
        contract-event-ch (chan)]
    (async done
      (dispatch [:initialize])
      (dispatch [:blockchain-balances balance-ch])
      (dispatch [:blockchain-fns coinbase-ch])
      (dispatch [:blockchain-filter block-ch])

      (let [compiled (web3-eth/compile-solidity w3 contract-source)]
        (is (map? compiled))
        (dispatch [:blockchain-contract-create
                   (:abi-definition (:info compiled))
                   {:data (:code compiled)
                    :gas gas-limit
                    :from (second (web3-eth/accounts w3))}
                   create-contract-ch]))
      (go
        (is (web3/address? (<! coinbase-ch)))
        (is (.greaterThan (<! balance-ch) 0))

        (<! create-contract-ch)

        (let [Contract (<! create-contract-ch)]
          (is (aget Contract "address"))
          (set! *contract* Contract))

        (is (string? (<! block-ch)))

        (dispatch [:contract-constant-fns *contract* constant-fn-ch])
        (is (.eq (<! constant-fn-ch) 45))

        (dispatch [:contract-events *contract* contract-event-ch])
        (dispatch [:contract-state-fns *contract* state-fn-ch1 state-fn-ch2])

        (is (string? (<! state-fn-ch1)))
        (is (map? (<! state-fn-ch1)))

        (is (string? (<! state-fn-ch2)))
        (is (map? (<! state-fn-ch2)))

        (is (.eq (:num (<! contract-event-ch)) 15))
        (is (.eq (web3-eth/contract-call *contract* :b) 15))
        (is (.eq (web3-eth/contract-call *contract* :c) 42))

        (dispatch [:contract-constant-fns *contract* constant-fn-ch])
        (is (.eq (<! constant-fn-ch) 135))

        (dispatch [:contract-events-stop-watching])
        (dispatch [:blockchain-filter-stop-watching])

        (done)))))