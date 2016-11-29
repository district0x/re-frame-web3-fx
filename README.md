# re-frame-web3-fx

This is [re-frame](https://github.com/Day8/re-frame) library, which contains several [Effect Handlers](https://github.com/Day8/re-frame/tree/develop/docs) for working with [Ethereum](https://ethereum.org/) blockchain [Web3 API](https://github.com/ethereum/wiki/wiki/JavaScript-API), using under the hood clojurescript interop library [cljs-web3](https://github.com/madvas/cljs-web3)

## See also
* [How to create decentralised apps with Clojurescript re-frame and Ethereum](https://medium.com/@matus.lestan/how-to-create-decentralised-apps-with-clojurescript-re-frame-and-ethereum-81de24d72ff5#.kul24x62l)

## Installation
```clojure
; Add to dependencies (requires re-frame >= v0.8.0)
[madvas.re-frame/web3-fx "0.1.2"]
```
```clojure
(ns my.app
  (:require [cljsjs.web3] ; You only need this, if you don't use MetaMask extension or Mist browser
            [madvas.re-frame.web3-fx])
```

## Usage
Following effect handlers are available:
#### :web3-fx.blockchain/fns
Use this to call any function from [cljs-web3](https://github.com/madvas/cljs-web3), which expects callback.
To `:fns` pass vector of vectors, describing which functions + args you want to call, last two items passed are on-success and on-error dispatch. Dispatches can always be one keyword or a vector as you'd pass to re-frame's `dispatch`. Note, you don't need to pass web3 object as a function arg to each function.
For example to create a new contract:
```clojure
(reg-event-fx
  :create-contract
  (fn [_ [_ abi bin]]
    {:web3-fx.blockchain/fns
     {:web3 w3
      :fns [[cljs-web3.eth/contract-new abi
             {:data bin
              :gas 4500000
              :from "0x6fce64667819c82a8bcbb78e294d7b444d2e1a29"}
             :contract-created
             :contract-create-error]]}}))
             
(reg-event-fx
  :contract-created
  (fn [_ [_ Contract]]
    (.log js/console Contract)
    {}))          
```

#### :web3-fx.blockchain/filter
Use this to setup blockchain [filter](https://github.com/ethereum/wiki/wiki/JavaScript-API#web3ethfilter) as you'd have done with `web3.eth.filter(options, callback);` in JS.
```clojure
(reg-event-fx
  :start-blockchain-filter
  (fn [_ [_ some-param]]
    {:web3-fx.blockchain/filter
     {:web3 web3
      :db-path [:some :path :to :blockchain-filter] ; This is where filter will be stored in your DB, so later can be stopped
      :blockchain-filter-opts "latest"
      :dispatches [[:block-loaded some-param] :block-load-error] ; on-success and on-error dispatches 
      }}))
```

#### :web3-fx.blockchain/filter-stop-watching
This is to stop previously setup filter
```clojure
(reg-event-fx
  :stop-blockchain-filter
  (fn []
    {:web3-fx.blockchain/filter-stop-watching [:some :path :to :blockchain-filter]}))
```
#### :web3-fx.blockchain/balances
This one is to obtain balance from address(es). You can also pass `:watch? true` and it will setup blockchain filter and calling your dispatch with a new balance after every new block. When you pass `:watch? true` you must also provide `:db-path` so filter can be saved.
```clojure
(reg-event-fx
  :get-balances
  (fn []
    {:web3-fx.blockchain/balances
     {:web3 web3
      :addresses ["0x6fce64667819c82a8bcbb78e294d7b444d2e1a29"
                  "0xe206f52728e2c1e23de7d42d233f39ac2e748977"]
      :watch? true
      :blockchain-filter-opts {:from-block 0 :to-block "latest"}
      :db-path [:balances]
      :dispatches [:balance-loaded :balance-load-error]}}))
      
(reg-event-fx
  :balance-loaded
  (fn [_ [_ balance address]]
    {}))
```
#### :web3-fx.contract/constant-fns
This one is to call your contract's constant methods (ones that doesn't change contract state and can return value). Method name is passed as kebab-cased keyword, then goes arguments, and last 2 items are on-success and on-error dispatches.
```clojure
(reg-event-fx
  :call-contract-constant-fns
  (fn [_ [_ contract-instance some-arg some-other-arg]]
    {:web3-fx.contract/constant-fns
     {:fns [[contract-instance :some-method some-arg some-other-arg :some-method-result :some-method-error]
            [contract-instance :multiply 9 6 :multiply-result :multiply-error]]}}))
```
#### :web3-fx.contract/state-fns
This is to call state changing methods of your contract (ones you need to pay gas to execute). Again, first in `:fn` is kebab-cased name of contract method. Then goes args. After args you pass options related to transaction. Then we have 3 dispatches. First one is called right after user confirms transaction. Second one is called if user rejected transaction. And the last one is called after transaction has been processed by blockchain and [transaction receipt](https://github.com/ethereum/wiki/wiki/JavaScript-API#web3ethgettransactionreceipt) is available. To get a receipt, a blockchain filter needs to be setup. This library does it for you, but you need to provide `:db-path` where filter can be saved, for later removal. Note, getting transaction receipt is the only way, you can verify if your transaction didn't run out of gas or thrown error. Therefore it's essencial to always have callback for it.
```clojure
(reg-event-fx
  :contract-state-fn
  (fn [_ [_ contract-instance some-param]]
    {:web3-fx.contract/state-fns
     {:web3 web3
      :db-path [:change-settings-fn]
      :fns [[contract-instance
            :change-settings 20 10
           {:gas 4500000
            :from "0xe206f52728e2c1e23de7d42d233f39ac2e748977"}
           [:change-settings-sent some-param]
           :change-settings-error
           [:change-settings-transaction-receipt-loaded some-param]]]}}))
           
(reg-event-fx
  :chainge-settings-sent
  (fn [_ [_  some-param transaction-hash]]
    {}))

(reg-event-fx
  :change-settings-transaction-receipt-loaded
  (fn [_ [_ some-param transaction-receipt]]
    {}))
```

#### :web3-fx.contract/events
With this, you setup listeners for contract events. Again, you need to pass `:db-path`, where listeners will be saved for later removal. First see how contract events are setup in JS: [Contract Events](https://github.com/ethereum/wiki/wiki/JavaScript-API#contract-events). Into `:events` you pass vector of vectors of events you want to listen to. 
Event vector can consist of 5 or 6 items:
```clojure
[contract-instance ; Your contract instance
event-filter-id ; (optional) Uniquely identifies filter, so it can be stopped later
event-name ; kebab-cased event name as in your contract. If you don't provide event-filter-id, this will be used as that.
filter-opts ; Filter events by indexed param
blockchain-filter-opts ; Filter events by blockchain opts
on-success
on-error]
```
If you pass same event id as already exists, old one will be stopped and new started. 
```clojure
(reg-event-fx
  :contract-events
  (fn [{:keys [db]} [_ contract-instance]]
    {:web3-fx.contract/events
     {:db db
      :db-path [:contract-events]
      :events [[contract-instance :on-settings-changed {} "latest" :on-settings-changed :on-settings-change-error]
               [contract-instance :some-event-id-1 :on-some-event {:some-param 1} "latest" :on-some-event-success :on-some-event-error]
               [contract-instance :some-event-id-2 :on-some-event {} {:from-block 0 :to-block 99} :on-some-event-success :on-some-event-error]]}}))
               
(reg-event-fx
  :on-settings-changed
  (fn [_ [_ new-settings]]
    {}))
```
#### :web3-fx.contract/events-stop-watching
This way you can stop previously setup event listeners.
```clojure
(reg-event-fx
  :contract-events-stop-watching
  (fn [{:keys [db]} _]
    {:web3-fx.contract/events-stop-watching
     {:db db
      :db-path [:contract-events]
      :event-ids [:on-settings-changed :some-event-id-1]}}))
```

## DAPPS using re-frame-web3-fx
* [emojillionaire](https://github.com/madvas/emojillionaire)


