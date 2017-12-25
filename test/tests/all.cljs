(ns tests.all
  (:require [cljs.core.async :refer [<! >! chan]]
            [cljs-web3.core :as web3]
            [cljs-web3.db :as web3-db]
            [cljs-web3.eth :as web3-eth]
            [cljs-web3.evm :as web3-evm]
            [cljs-web3.net :as web3-net]
            [cljs-web3.personal :as web3-personal]
            [cljs-web3.shh :as web3-shh]
            [cljs.test :refer-macros [deftest is testing run-tests use-fixtures async]]
            [cljsjs.web3]
            [day8.re-frame.test :refer [run-test-async wait-for run-test-sync]]
            [district0x.re-frame.web3-fx]
            [re-frame.core :refer [reg-event-fx console dispatch trim-v reg-sub subscribe]]
            )
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def web3 (web3/create-web3 "http://localhost:8549/"))
(def gas-limit 4500000)

(def interceptors [trim-v])

;; ./resources/TestContract.sol
(def contract-abi (clj->js (js/JSON.parse "[{\"constant\":true,\"inputs\":[],\"name\":\"mintingFinished\",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"_spender\",\"type\":\"address\"},{\"name\":\"_value\",\"type\":\"uint256\"}],\"name\":\"approve\",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":true,\"inputs\":[],\"name\":\"totalSupply\",\"outputs\":[{\"name\":\"\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"_from\",\"type\":\"address\"},{\"name\":\"_to\",\"type\":\"address\"},{\"name\":\"_value\",\"type\":\"uint256\"}],\"name\":\"transferFrom\",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"_to\",\"type\":\"address\"},{\"name\":\"_amount\",\"type\":\"uint256\"}],\"name\":\"mint\",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"_spender\",\"type\":\"address\"},{\"name\":\"_subtractedValue\",\"type\":\"uint256\"}],\"name\":\"decreaseApproval\",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":true,\"inputs\":[{\"name\":\"_owner\",\"type\":\"address\"}],\"name\":\"balanceOf\",\"outputs\":[{\"name\":\"balance\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[],\"name\":\"finishMinting\",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":true,\"inputs\":[],\"name\":\"owner\",\"outputs\":[{\"name\":\"\",\"type\":\"address\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"_to\",\"type\":\"address\"},{\"name\":\"_value\",\"type\":\"uint256\"}],\"name\":\"transfer\",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"_spender\",\"type\":\"address\"},{\"name\":\"_addedValue\",\"type\":\"uint256\"}],\"name\":\"increaseApproval\",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":true,\"inputs\":[{\"name\":\"_owner\",\"type\":\"address\"},{\"name\":\"_spender\",\"type\":\"address\"}],\"name\":\"allowance\",\"outputs\":[{\"name\":\"\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"newOwner\",\"type\":\"address\"}],\"name\":\"transferOwnership\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":true,\"name\":\"to\",\"type\":\"address\"},{\"indexed\":false,\"name\":\"amount\",\"type\":\"uint256\"}],\"name\":\"Mint\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[],\"name\":\"MintFinished\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":true,\"name\":\"previousOwner\",\"type\":\"address\"},{\"indexed\":true,\"name\":\"newOwner\",\"type\":\"address\"}],\"name\":\"OwnershipTransferred\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":true,\"name\":\"owner\",\"type\":\"address\"},{\"indexed\":true,\"name\":\"spender\",\"type\":\"address\"},{\"indexed\":false,\"name\":\"value\",\"type\":\"uint256\"}],\"name\":\"Approval\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":true,\"name\":\"from\",\"type\":\"address\"},{\"indexed\":true,\"name\":\"to\",\"type\":\"address\"},{\"indexed\":false,\"name\":\"value\",\"type\":\"uint256\"}],\"name\":\"Transfer\",\"type\":\"event\"}]")))
(def contract-bin "0x606060405260038054600160a860020a03191633600160a060020a0316179055610a078061002e6000396000f3006060604052600436106100c45763ffffffff7c010000000000000000000000000000000000000000000000000000000060003504166305d2035b81146100c9578063095ea7b3146100f057806318160ddd1461011257806323b872dd1461013757806340c10f191461015f578063661884631461018157806370a08231146101a35780637d64bcb4146101c25780638da5cb5b146101d5578063a9059cbb14610204578063d73dd62314610226578063dd62ed3e14610248578063f2fde38b1461026d575b600080fd5b34156100d457600080fd5b6100dc61028e565b604051901515815260200160405180910390f35b34156100fb57600080fd5b6100dc600160a060020a036004351660243561029e565b341561011d57600080fd5b61012561030a565b60405190815260200160405180910390f35b341561014257600080fd5b6100dc600160a060020a0360043581169060243516604435610310565b341561016a57600080fd5b6100dc600160a060020a0360043516602435610492565b341561018c57600080fd5b6100dc600160a060020a036004351660243561059f565b34156101ae57600080fd5b610125600160a060020a0360043516610699565b34156101cd57600080fd5b6100dc6106b4565b34156101e057600080fd5b6101e861073f565b604051600160a060020a03909116815260200160405180910390f35b341561020f57600080fd5b6100dc600160a060020a036004351660243561074e565b341561023157600080fd5b6100dc600160a060020a0360043516602435610849565b341561025357600080fd5b610125600160a060020a03600435811690602435166108ed565b341561027857600080fd5b61028c600160a060020a0360043516610918565b005b60035460a060020a900460ff1681565b600160a060020a03338116600081815260026020908152604080832094871680845294909152808220859055909291907f8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b9259085905190815260200160405180910390a350600192915050565b60005481565b6000600160a060020a038316151561032757600080fd5b600160a060020a03841660009081526001602052604090205482111561034c57600080fd5b600160a060020a038085166000908152600260209081526040808320339094168352929052205482111561037f57600080fd5b600160a060020a0384166000908152600160205260409020546103a8908363ffffffff6109b316565b600160a060020a0380861660009081526001602052604080822093909355908516815220546103dd908363ffffffff6109c516565b600160a060020a03808516600090815260016020908152604080832094909455878316825260028152838220339093168252919091522054610425908363ffffffff6109b316565b600160a060020a03808616600081815260026020908152604080832033861684529091529081902093909355908516917fddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef9085905190815260200160405180910390a35060019392505050565b60035460009033600160a060020a039081169116146104b057600080fd5b60035460a060020a900460ff16156104c757600080fd5b6000546104da908363ffffffff6109c516565b6000908155600160a060020a038416815260016020526040902054610505908363ffffffff6109c516565b600160a060020a0384166000818152600160205260409081902092909255907f0f6798a560793a54c3bcfe86a93cde1e73087d944c0ea20544137d41213968859084905190815260200160405180910390a2600160a060020a03831660007fddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef8460405190815260200160405180910390a350600192915050565b600160a060020a033381166000908152600260209081526040808320938616835292905290812054808311156105fc57600160a060020a033381166000908152600260209081526040808320938816835292905290812055610633565b61060c818463ffffffff6109b316565b600160a060020a033381166000908152600260209081526040808320938916835292905220555b600160a060020a0333811660008181526002602090815260408083209489168084529490915290819020547f8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925915190815260200160405180910390a35060019392505050565b600160a060020a031660009081526001602052604090205490565b60035460009033600160a060020a039081169116146106d257600080fd5b60035460a060020a900460ff16156106e957600080fd5b6003805474ff0000000000000000000000000000000000000000191660a060020a1790557fae5184fba832cb2b1f702aca6117b8d265eaf03ad33eb133f19dde0f5920fa0860405160405180910390a150600190565b600354600160a060020a031681565b6000600160a060020a038316151561076557600080fd5b600160a060020a03331660009081526001602052604090205482111561078a57600080fd5b600160a060020a0333166000908152600160205260409020546107b3908363ffffffff6109b316565b600160a060020a0333811660009081526001602052604080822093909355908516815220546107e8908363ffffffff6109c516565b600160a060020a0380851660008181526001602052604090819020939093559133909116907fddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef9085905190815260200160405180910390a350600192915050565b600160a060020a033381166000908152600260209081526040808320938616835292905290812054610881908363ffffffff6109c516565b600160a060020a0333811660008181526002602090815260408083209489168084529490915290819020849055919290917f8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b92591905190815260200160405180910390a350600192915050565b600160a060020a03918216600090815260026020908152604080832093909416825291909152205490565b60035433600160a060020a0390811691161461093357600080fd5b600160a060020a038116151561094857600080fd5b600354600160a060020a0380831691167f8be0079c531659141344cd1fd0a4f28419497f9722a3daafe3b4186f6b6457e060405160405180910390a36003805473ffffffffffffffffffffffffffffffffffffffff1916600160a060020a0392909216919091179055565b6000828211156109bf57fe5b50900390565b6000828201838110156109d457fe5b93925050505600a165627a7a72305820749e47101bc93b8c11c90834f85ed4d495ba036d551d6a8aaeb3331198973e650029")


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


(deftest contract-tests
  (run-test-async
    (let [contract (subscribe [::contract])
          accounts (subscribe [::accounts])
          total-supply (subscribe [::total-supply])
          mint-event-args (subscribe [::mint-event-args])]
      (dispatch [::load-accounts])

      (wait-for [::accounts-loaded ::error]
        (is (not (empty? @accounts)))

        (dispatch [::deploy-contract contract-abi {:data contract-bin
                                                   :gas gas-limit
                                                   :from (first @accounts)}])

        (wait-for [::contract-deployed ::error]
          (is (string? (aget @contract "address")))

          (dispatch [::get-token-total-supply])

          (wait-for [::token-total-supply-result ::error]
            (is (= 0 @total-supply))

            (dispatch [::mint-token {:from (first @accounts)
                                     :to (second @accounts)
                                     :amount (web3/to-wei 10 :ether)}])
            (wait-for [[::mint-tx-hash-loaded ::mint-token-receipt-loaded ::token-minted] :error]
              (dispatch [::watch-mint {:to (second @accounts)}])

              (wait-for [::token-minted-event ::error]
                (is (= {:to (second @accounts)
                        :amount (web3/to-wei 10 :ether)}
                       (update @mint-event-args :amount str)))

                (dispatch [::load-token-balances [(second @accounts)]])

                (wait-for [::token-balance-loaded ::error]
                  (= @(subscribe [::token-balance (second @accounts)])
                     (web3/to-wei 10 :ether))

                  (testing "Watching token balance works"
                    (dispatch [::transfer-token {:from (second @accounts)
                                                 :to (last @accounts)
                                                 :amount (web3/to-wei 4 :ether)}])

                    (wait-for [::token-balance-loaded ::error]
                      (dispatch [::stop-watching-all])
                      (= @(subscribe [::token-balance (second @accounts)])
                         (web3/to-wei 6 :ether)))))))))))))

(deftest ether-tests
  (run-test-async
    (let [accounts (subscribe [::accounts])]
      (dispatch [::load-accounts])

      (wait-for [::accounts-loaded ::error]
        (is (not (empty? @accounts)))

        (dispatch [::transfer-ether {:from (first @accounts)
                                     :to (second @accounts)
                                     :amount (web3/to-wei 200 :ether)}])

        (testing "Expect error because user doesn't have that much ether"
          (wait-for [::error ::ether-transferred]

            (dispatch [::transfer-ether {:from (first @accounts)
                                         :to (second @accounts)
                                         :amount (web3/to-wei 1 :ether)}])

            (wait-for [::ether-transferred ::error]
              (dispatch [::load-ether-balances [(second @accounts)]])

              (wait-for [::ether-balance-loaded ::error]
                (is (< 100 (web3/from-wei @(subscribe [::balance (second @accounts)]) :ether)))))))))))