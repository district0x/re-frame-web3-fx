(ns district0x.re-frame.web3-fx
  (:require
    [cljs.core.async :refer [<! >! chan]]
    [cljs-web3-next.eth :as web3-eth]
    [cljs-web3-next.async.eth :as web3-eth-async]
    [cljs.spec.alpha :as s]
    [re-frame.core :refer [reg-fx dispatch console reg-event-db reg-event-fx]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def ^:dynamic *listeners* (atom {}))

(defn- block-filter-opts? [x]
  (or (map? x) (string? x) (nil? x)))

(s/def ::id any?)
(s/def ::instance (complement nil?))
(s/def ::dispatch vector?)
(s/def ::contract-fn-arg any?)
(s/def ::address string?)
(s/def ::watch? (s/nilable boolean?))
(s/def ::block-filter-opts block-filter-opts?)
(s/def ::web3 (complement nil?))
(s/def ::event-ids sequential?)
(s/def ::fn #(or (fn? %) (keyword? %) (string? %)))
(s/def ::args (s/coll-of ::contract-fn-arg))
(s/def ::on-success ::dispatch)
(s/def ::on-error ::dispatch)
(s/def ::on-tx-hash ::dispatch)
(s/def ::on-tx-hash-error ::dispatch)
(s/def ::on-tx-receipt ::dispatch)
(s/def ::on-tx-success ::dispatch)
(s/def ::on-tx-error ::dispatch)
(s/def ::tx-opts map?)
(s/def ::event keyword?)
(s/def ::event-id any?)
(s/def ::event-filter-opts (s/nilable map?))
(s/def ::block-filter-opts block-filter-opts?)
(s/def ::tx-hashes (s/coll-of string?))

(s/def ::fns
  (s/coll-of (s/nilable (s/keys :opt-un [::args
                                         ::tx-opts
                                         ::on-success
                                         ::on-error
                                         ::on-tx-hash
                                         ::on-tx-hash-error
                                         ::on-tx-receipt
                                         ::on-tx-success
                                         ::on-tx-error
                                         ::fn
                                         ::instance]))))
(s/def ::call (s/keys :req-un [::web3 ::fns]))


(s/def ::events (s/coll-of (s/nilable (s/keys :req-un [::instance ::event]
                                              :opt-un [::id
                                                       ::event-filter-opts
                                                       ::block-filter-opts
                                                       ::on-success
                                                       ::on-error]))))
(s/def ::watch-events (s/keys :req-un [::events]))


(s/def ::transactions
  (s/coll-of (s/nilable (s/keys :req-un [::tx-hash]
                                :opt-un [::on-tx-receipt ::on-tx-success ::on-tx-error ::id]))))
(s/def ::watch-transactions (s/keys :req-un [::web3 ::transactions]))

(s/def ::addresses
  (s/coll-of (s/nilable (s/keys :req-un [::address ::on-success]
                                :opt-un [::on-error ::watch? ::id]))))

(s/def ::get-balances (s/keys :req-un [::addresses ::web3]))


(s/def ::listen (s/keys :req-un [::on-success ::on-error ::web3 ::block-filter-opts]))


(defn- dispach-fn [on-success on-error & args]
  (fn [err res]
    (if err
      (dispatch (vec (concat on-error (cons err args))))
      (dispatch (vec (concat on-success (cons res args)))))))


(defn- contract-event-dispach-fn [on-success on-error]
  (fn [err res]
    (if err
      (dispatch (vec (concat on-error [err])))
      (dispatch (vec (concat on-success [(:args res) res]))))))


(defn- stop-listener! [id]
  (when-let [filters (get @*listeners* id)]
    (swap! *listeners* dissoc id)
    (doseq [filter filters]
      (web3-eth/stop-watching! filter (fn [])))))


(defn- start-listener! [{:keys [:web3 :id :block-filter-opts :callback]}]
  (let [id (if id id callback)]
    (stop-listener! id)
    (swap! *listeners* update id conj (web3-eth/filter web3 block-filter-opts callback))
    id))


(defn- start-event-listener! [{:keys [:instance :id :event :event-filter-opts :block-filter-opts :callback]}]
  (let [id (if id id callback)]
    (stop-listener! id)
    (->> (web3-eth/contract-call
           instance
           event
           event-filter-opts
           block-filter-opts
           callback)
      (swap! *listeners* update id conj))
    id))

;; kinda dumb explicit implementation, but makes a nice API

(defn- same-sender? [tx target-tx]
  (= (:from tx) (:from target-tx)))

(defn- same-hash? [tx target-tx]
  (= (:hash tx) (:hash target-tx)))

(defn- same-input? [tx target-tx]
  (= (:input tx) (:input target-tx)))

(defn- same-nonce? [tx target-tx]
  (= (:nonce tx) (:nonce target-tx)))

(reg-fx
  :web3/watch-events
  (fn [{:keys [:events] :as params}]
    (s/assert ::watch-events params)
    (doseq [{:keys [:id :instance :block-filter-opts :event-filter-opts :on-success :on-error
                    :event]} events]
      (start-event-listener!
        {:instance instance
         :id id
         :event event
         :event-filter-opts event-filter-opts
         :block-filter-opts block-filter-opts
         :callback (contract-event-dispach-fn on-success on-error)}))))


(reg-fx
  :web3/watch-transactions
  (fn [{:keys [:web3 :transactions] :as params}]
    (s/assert ::watch-transactions params)
    (doseq [{:keys [:tx-hash :on-tx-receipt :on-tx-success :on-tx-error :id]} transactions]
      (let [listener-id (or id (rand 9999999))
            dispatch-on-tx-receipt-fn (fn [params] (throw (js/Error. "Find another way to do this. Web3.eth.filter was removed" params)))]
        (start-listener!
          {:web3 web3
           :id listener-id
           :block-filter-opts "latest"
           :callback (dispatch-on-tx-receipt-fn
                       {:id listener-id
                        :on-tx-receipt on-tx-receipt
                        :on-tx-success on-tx-success
                        :on-tx-error on-tx-error
                        :tx-hash tx-hash
                        :web3 web3})})))))


(defn constant-method?
  "Detect from contract ABI whether a smart contract method is constant and can
   be called instead of sending a transaction.
   Based on
     https://web3js.readthedocs.io/en/v1.7.1/glossary.html#specification
   And how it used to be done in Web3.js 0.20.x
     https://github.com/ChainSafe/web3.js/blob/0.20.7/lib/web3/function.js#L40"
  [contract method]
  (let [abi (aget contract "_jsonInterface")
        method-abi (first (filter #(= (aget % "name") (name method)) abi))
        mutability (aget method-abi "stateMutability")
        constant (aget "constant" method-abi)]
    (or (= mutability "view") (= mutability "pure") constant false)))

(reg-fx
  :web3/call
  (fn [{:keys [:web3 :fns] :as params}]
    (s/assert ::call params)
    (doseq [{:keys [:instance :fn :args :on-success :on-error]} (remove nil? fns)]
      (let [call-contract-method? (not (nil? instance))
            call-on-contract-instance #(apply web3-eth/contract-call (concat [instance fn] args [(dispach-fn on-success on-error)]))
            call-on-web3-instance #(apply fn (concat [web3] args [(dispach-fn on-success on-error)]))]
        (if call-contract-method? (call-on-contract-instance) (call-on-web3-instance))))))

(defn safe-dispatch-one-many [re-event-one re-event-many result]
  (let [dispatch-single (fn [re-event result] (dispatch (conj (vec re-event-one) result)))]
    (when re-event-one (dispatch-single re-event-one result))
    (when re-event-many (doseq [re-event re-event-many] (dispatch-single re-event result)))))

(defn dispatch-on-tx-promi-event [tx-promi-event {:keys [:on-tx-hash :on-tx-hash-n
                                                         :on-tx-hash-error :on-tx-hash-error-n
                                                         :on-tx-success :on-tx-success-n
                                                         :on-tx-receipt :on-tx-receipt-n
                                                         :on-tx-error :on-tx-error-n] :as re-events}]
  (.once tx-promi-event "transactionHash" (partial safe-dispatch-one-many on-tx-hash on-tx-hash-n))
  (.once tx-promi-event "receipt" (partial safe-dispatch-one-many on-tx-receipt on-tx-receipt-n))
  ; The difference between tx-error and tx-hash-error is whether the Error
  ; object passed to them has .receipt property. Since it's used for logging
  ; and in neither case the Tx was successful, for simplicity I left the
  ; implementation the same
  ; https://web3js.readthedocs.io/en/v1.7.1/web3-eth-contract.html#id36
  (.once tx-promi-event "error" (partial safe-dispatch-one-many on-tx-error on-tx-error-n))
  (.once tx-promi-event "error" (partial safe-dispatch-one-many on-tx-hash-error on-tx-hash-error-n)))

(def tx-result-re-events [:on-tx-hash :on-tx-hash-n
                          :on-tx-hash-error :on-tx-hash-error-n
                          :on-tx-success :on-tx-success-n
                          :on-tx-receipt :on-tx-receipt-n
                          :on-tx-error :on-tx-error-n])
(reg-fx
  :web3/send
  (fn [{:keys [:web3 :fns] :as params}]
    (s/assert ::call params)
    (doseq [{method :fn
             :keys [:instance :args :tx-opts
                    :on-tx-success :on-tx-error
                    :on-success :on-error] :as method-args} (remove nil? fns)]
      (if instance
        (if tx-opts
          (dispatch-on-tx-promi-event (web3-eth/contract-send instance method args tx-opts)
                                      (select-keys method-args tx-result-re-events))
          (web3-eth/contract-call instance method args (dispach-fn on-success on-error)))
        (apply method (concat [web3] args [(dispach-fn on-success on-error)]))))))

(reg-fx
  :web3/get-balances
  (fn [{:keys [:addresses :web3] :as params}]
    (s/assert ::get-balances params)
    (doseq [{:keys [:address :on-success :on-error :watch? :instance :id]} addresses]

      (if-not instance
        (web3-eth/get-balance web3 address (dispach-fn on-success on-error))
        (web3-eth/contract-call instance :balance-of address (dispach-fn on-success on-error)))

      (when (and watch? (seq addresses))
        (if-not instance
          (start-listener!
            {:web3 web3
             :id id
             :block-filter-opts "latest"
             :callback (fn [err]
                         (when-not err
                           (web3-eth/get-balance web3 address (dispach-fn on-success on-error))))})
          (do
            (start-event-listener!
              {:instance instance
               :id id
               :event :Transfer
               :event-filter-opts {:from address}
               :block-filter-opts "latest"
               :callback (fn []
                           (web3-eth/contract-call instance :balance-of address (dispach-fn on-success on-error)))})
            (start-event-listener!
              {:instance instance
               :id id
               :event :Transfer
               :event-filter-opts {:to address}
               :block-filter-opts "latest"
               :callback (fn []
                           (web3-eth/contract-call instance :balance-of address (dispach-fn on-success on-error)))})))))))

(reg-fx
  :web3/watch-blocks
  (fn [{:keys [:web3 :id :block-filter-opts :on-success :on-error] :as config}]
    (s/assert ::listen config)
    (start-listener!
      {:web3 web3
       :id id
       :block-filter-opts block-filter-opts
       :callback (dispach-fn on-success on-error)})))

(reg-fx
  :web3/stop-watching
  (fn [{:keys [:ids]}]
    (doseq [id ids]
      (stop-listener! id))))


(reg-fx
  :web3/stop-watching-all
  (fn []
    (doseq [id (keys @*listeners*)]
      (stop-listener! id))))
