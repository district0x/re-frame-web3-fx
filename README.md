# re-frame-web3-fx

[![Build Status](https://travis-ci.org/district0x/re-frame-web3-fx.svg?branch=master)](https://travis-ci.org/district0x/re-frame-web3-fx)

[re-frame](https://github.com/Day8/re-frame) [Effectful Handlers](https://github.com/Day8/re-frame/blob/develop/docs/EffectfulHandlers.md) to work with [Ethereum](https://ethereum.org/) blockchain [Web3 API](https://github.com/ethereum/wiki/wiki/JavaScript-API), using [cljs-web3](https://github.com/madvas/cljs-web3)

## Installation
Add `[district0x.re-frame/web3-fx "1.0.7"]` into your project.clj
Include `[district0x.re-frame.web3-fx]` in your CLJS file

```clojure
(ns my.app
  (:require [cljsjs.web3] ; You only need this, if you don't use MetaMask extension or Mist browser
            [district0x.re-frame.web3-fx]))
```

## Breaking changes 0.2.3 -> 1.0.0
This library was completely rewritten on version upgrade 0.2.3 -> 1.0.0. API was greatly changed (simplified).
Reasons for breakage were also unerlying changes in Ethereum (fork to Byzantium) and preparations for web3 1.0.0.
I deeply appologize, but this was absolutely necessary.

## API Overview

- [:web3/call](#web3call)
- [:web3/get-balances](#web3get-balances)
- [:web3/watch-events](#web3watch-events)
- [:web3/watch-transactions](#web3watch-transactions)
- [:web3/watch-blocks](#web3watch-blocks)
- [:web3/stop-watching](#web3stop-watching)
- [:web3/stop-watching-all](#web3stop-watching-all)


## Usage
Following effect handlers are available:
#### `:web3/call`
Use it to call any function from [cljs-web3](https://github.com/madvas/cljs-web3) or any smart contract function.
Calling [cljs-web3](https://github.com/madvas/cljs-web3) function:
```clojure
(reg-event-fx
  ::load-accounts
  (fn [{:keys [:db]} []]
    {:web3/call {:web3 (:web3 db)
                 :fns [{:fn cljs-web3.eth/accounts
                        :args []
                        :on-success [::accounts-loaded]
                        :on-error [::error]}]}}))
```
Calling **constant** smart-contract function. In this case getting total supply of a ERC20 Token:
```clojure
(reg-event-fx
  ::get-token-total-supply
  (fn [{:keys [:db]}]
    {:web3/call {:web3 (:web3 db)
                 :fns [{:instance (:token-contract-instance db)
                        :fn :total-supply
                        :on-success [::token-total-supply-result]
                        :on-error [::error]}]}}))
```
Calling **state changing** smart-contract function, aka sending a transaction to the network. In this case calling [mint](https://github.com/district0x/re-frame-web3-fx/blob/master/resources/MintableToken.sol#L34)
function of MintableToken. Notice there's no `on-success`, `on-error`. Given callbacks are executed at following situations:
* `:on-tx-hash` When tx is successfully sent to the network. Receives tx-hash in parameters.
* `:on-tx-hash-error` When tx wasn't send to the network. Usually user rejected to sign.
* `:on-tx-success` When tx was processed without error. Receives receipt in parameters.
* `:on-tx-error` When there was an error during processing a transaction. Receives receipt in parameters.
* `:on-tx-receipt` General callback when tx was processed. Either with error or not. Receives receipt in parameters.
(You don't need to use all of them, only ones you need) <br>

All of these callbacks have their respective multi-event callbacks i.e.:

* `:on-tx-hash-n`
* `:on-tx-hash-error-n`
* `:on-tx-success-n`
* `:on-tx-error-n`
* `:on-tx-receipt-n`

```clojure
(reg-event-fx
  ::mint-token
  (fn [{:keys [:db]} [_ {:keys [:to :amount :from]}]]
    {:web3/call {:web3 (:web3 db)
                 :fns [{:instance (:token-contract-instance db)
                        :fn :mint
                        :args [to amount]
                        :tx-opts {:from from
                                  :gas 4500000}
                        :on-tx-hash-n [[::tx-send-success] [::extra]]
                        :on-tx-hash-error [::tx-send-failed]
                        :on-tx-success [::token-minted]
                        :on-tx-error [::tx-failed]
                        :on-tx-receipt [::tx-receipt-loaded]}]}}))
```

#### `:web3/get-balances`
Gets balance of Ether or ERC20 token. Optionally you can pass `:watch? true`, so the callback will be fired everytime
the balance changes.
Getting and watching balance or Ether:
```clojure
(reg-event-fx
  ::load-ether-balances
  (fn [{:keys [:db]} [_ addresses]]
    {:web3/get-balances {:web3 (:web3 db)
                         :addresses (for [address addresses]
                                      {:id (str "balance-" address) ;; If you watch?, pass :id so you can stop watching later
                                       :address address
                                       :watch? true
                                       :on-success [::ether-balance-loaded address]
                                       :on-error [::error]})}}))
```
Getting and watching balance of a ERC20 Token. Notice you need to pass `:instance` of a ERC20 contract
```clojure
(reg-event-fx
  ::load-token-balances
  (fn [{:keys [:db]} [_ addresses]]
    {:web3/get-balances {:web3 (:web3 db)
                         :addresses (for [address addresses]
                                      {:id (str "balance-" address) ;; If you watch?, pass :id so you can stop watching later
                                       :address address
                                       :instance (:token-contract-instance db)
                                       :watch? true
                                       :on-success [::token-balance-loaded address]
                                       :on-error [::error]})}}))
```

#### `:web3/watch-events`
Listens to smart-contract events. Callback receives event `:args` as first param and complete event data as a second.
In this example we watch [Mint](https://github.com/district0x/re-frame-web3-fx/blob/master/resources/MintableToken.sol#L17) event of MintableToken
```clojure
(reg-event-fx
  ::watch-mint
  (fn [{:keys [:db]} [_ {:keys [:to]}]]
    {:web3/watch-events {:events [{:id :mint-watcher
                                   :event :Mint
                                   :instance (:token-contract-instance db)
                                   :block-filter-opts {:from-block 0 :to-block "latest"}
                                   :event-filter-opts {:to to}
                                   :on-success [::token-mint-event]
                                   :on-error [::error]}]}}))
```

#### `:web3/watch-transactions`
Sets up listener until transaction receipt is available. Callbacks are fired same way as in `:web3/call` for
state-changing contract functions.

```clojure
(reg-event-fx
  ::watch-transaction
  interceptors
  (fn [{:keys [:db]} [tx-hash]]
    {:web3/watch-transactions {:web3 (:web3 db)
                               :transactions [{:id :my-watcher
                                               :tx-hash tx-hash
                                               :on-tx-success [::tx-success]
                                               :on-tx-error [::error]
                                               :on-tx-receipt [::tx-receipt]}]}}))
```

#### `:web3/watch-blocks`
Sets up listener with callback fired on each new Ethereum block.

```clojure
(reg-event-fx
    ::watch-blocks
    (fn [{:keys [:db]}]
      {:web3/watch-blocks {:id :my-watcher
                           :web3 (:web3 db)
                           :block-filter-opts "latest"
                           :on-success [::new-block]
                           :on-error [::error]}}))
```

#### `:web3/stop-watching`
In any effect handler above, where you could provide `:id`, you can use this effect handler to stop that listener.
```clojure
(reg-event-fx
    ::stop-watching
    (fn [{:keys [:db]}]
      {:web3/stop-watching {:ids [:my-watcher]}}))
```

#### `:web3/stop-watching-all`
Stops all listeners set up by all effect handlers.
```clojure
(reg-event-fx
    ::stop-watching
    (fn [{:keys [:db]}]
      {:web3/stop-watching-all true}))
```

## Running tests

1. On development machine
  - `npx shadow-cljs watch test-browser`
  - Open http://d0x-vm:6502
2. On CI (or headless env)
  - `npx shadow-cljs compile test-ci`
  - ```
    CHROME_BIN=`which chromium-browser` npx karma start --single-run`
  ```





