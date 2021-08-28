(ns district0x.re-frame.web3-fx
  (:require
    [cljs.core.async :refer [<! >! chan]]
    [cljs-web3.eth :as web3-eth]
    [cljs-web3.async.eth :as web3-eth-async]
    [cljs.spec.alpha :as s]
    [re-frame.core :refer [reg-fx dispatch console reg-event-db reg-event-fx]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def *listeners* (atom {}))

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

(defn- dispatch-on-tx-receipt-fn
  "get-transaction output:

  Returns a transaction object its hash transaction-hash:
  - hash: String, 32 Bytes - hash of the transaction.
  - nonce: Number - the number of transactions made by the sender prior to this
    one.
  - block-hash: String, 32 Bytes - hash of the block where this transaction was
                                   in. null when its pending.
  - block-number: Number - block number where this transaction was in. null when
                           its pending.
  - transaction-index: Number - integer of the transactions index position in the
                                block. null when its pending.
  - from: String, 20 Bytes - address of the sender.
  - to: String, 20 Bytes - address of the receiver. null when its a contract
                           creation transaction.
  - value: BigNumber - value transferred in Wei.
  - gas-price: BigNumber - gas price provided by the sender in Wei.
  - gas: Number - gas provided by the sender.
  - input: String - the data sent along with the transaction.
  "

  [{:keys [:web3 :id :tx-hash :on-tx-receipt-n :on-tx-receipt :on-tx-error
           :on-tx-error-n :on-tx-success-n :on-tx-success]}]
  (fn [err]
    (let [process-tx-receipt
           (fn [[err receipt]]
             (let [hash-replaced? (every? nil? [err receipt])
                   checked-receipt (or receipt {:transaction-hash tx-hash})]
               (when (or (:block-number receipt) hash-replaced?)
                (stop-listener! id)
                (when on-tx-receipt
                  (dispatch (conj (vec on-tx-receipt) checked-receipt)))
                (cond
                  (or (contains? #{"0x0" "0x00" 0} (:status checked-receipt)) hash-replaced?)
                  (do (when on-tx-error
                        (dispatch (conj (vec on-tx-error) checked-receipt)))
                      (when on-tx-error-n
                        (doseq [callback on-tx-error-n]
                          (dispatch (conj (vec callback) checked-receipt)))))

                  (contains? #{"0x1" "0x01" 1}  (:status checked-receipt))
                  (do (when on-tx-success
                        (dispatch (conj (vec on-tx-success) checked-receipt)))
                      (when on-tx-success-n
                        (doseq [callback on-tx-success-n]
                          (dispatch (conj (vec callback) checked-receipt)))))))))]
      (when-not err
       ;; search for a replacement or speed-up tx
        (go
          (let [block (second (<! (web3-eth-async/get-block web3 "latest" true)))
                txs  (:transactions block)
                target-tx (second (<! (web3-eth-async/get-transaction web3 tx-hash)))
                speed-up-tx (first (filter  (fn [tx]
                                              (and
                                               (same-sender? tx target-tx)
                                               (not (same-hash? tx target-tx))
                                               (same-nonce? tx target-tx)
                                               )) txs))
                mined-hash (if speed-up-tx (:hash speed-up-tx) tx-hash)]
            (process-tx-receipt (<! (web3-eth-async/get-transaction-receipt web3 mined-hash)))))))))


(defn- contract-state-call-callback [{:keys [:web3
                                             :on-tx-receipt-n
                                             :on-tx-hash-n :on-tx-hash-error-n
                                             :on-tx-success-n :on-tx-error-n
                                             :on-tx-receipt
                                             :on-tx-hash :on-tx-hash-error
                                             :on-tx-success :on-tx-error]}]
  (fn [err tx-hash]
    (if err
      (do (when on-tx-hash-error
            (dispatch (conj (vec on-tx-hash-error) err)))
          (when on-tx-hash-error-n
            (doseq [callback on-tx-hash-error-n]
              (dispatch (conj (vec callback) err)))))

      (do (when on-tx-hash
            (dispatch (conj (vec on-tx-hash) tx-hash)))
          (when on-tx-hash-n
            (doseq [callback on-tx-hash-n]
              (dispatch (conj (vec callback) tx-hash))))
          (let [listener-id (rand 9999999)]
            (start-listener!
             {:web3 web3
              :id listener-id
              :block-filter-opts "latest"
              :callback (dispatch-on-tx-receipt-fn {:id listener-id
                                                    :tx-hash tx-hash
                                                    :web3 web3
                                                    :on-tx-receipt-n on-tx-receipt-n
                                                    :on-tx-success-n on-tx-success-n
                                                    :on-tx-error-n on-tx-error-n
                                                    :on-tx-receipt on-tx-receipt
                                                    :on-tx-error on-tx-error
                                                    :on-tx-success on-tx-success})}))))))


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
      (let [listener-id (or id (rand 9999999))]
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


(reg-fx
  :web3/call
  (fn [{:keys [:web3 :fns] :as params}]
    (s/assert ::call params)
    (doseq [{:keys [:instance :fn :args :tx-opts
                    :on-tx-receipt-n
                    :on-tx-hash-n :on-tx-hash-error-n
                    :on-tx-success-n :on-tx-error-n
                    :on-tx-receipt
                    :on-tx-hash :on-tx-hash-error
                    :on-tx-success :on-tx-error
                    :on-success :on-error]} (remove nil? fns)]
      (if instance
        (if tx-opts
          (apply web3-eth/contract-call
                 (concat [instance fn]
                         args
                         [tx-opts]
                         [(contract-state-call-callback {:web3 web3
                                                         :on-tx-receipt on-tx-receipt
                                                         :on-tx-receipt-n on-tx-receipt-n
                                                         :on-tx-hash-n on-tx-hash-n
                                                         :on-tx-hash-error-n on-tx-hash-error-n
                                                         :on-tx-success-n on-tx-success-n
                                                         :on-tx-error-n on-tx-error-n
                                                         :on-tx-hash on-tx-hash
                                                         :on-tx-hash-error on-tx-hash-error
                                                         :on-tx-success on-tx-success
                                                         :on-tx-error on-tx-error})]))
          (apply web3-eth/contract-call
                 (concat [instance fn]
                         args
                         [(dispach-fn on-success on-error)])))
        (apply fn (concat [web3] args [(dispach-fn on-success on-error)]))))))


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
