(ns district0x.re-frame.web3-fx
  (:require
    [cljs.core.async :refer [<! >! chan]]
    [cljs-web3-next.eth :as web3-eth]
    [cljs-web3-next.core :as web3-core]
    [cljs-web3-next.utils :as web3-utils]
    [cljs-web3-next.async.eth :as web3-eth-async]
    [cljs.spec.alpha :as s]
    [re-frame.core :refer [reg-fx dispatch console reg-event-db reg-event-fx]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def ^:dynamic *listeners* (atom {}))

(defonce block-listener (atom nil))
(defonce block-listener-init? (atom false))
(def default-delay 1000)

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
(s/def ::watch-events (s/keys :req-un [::web3 ::events]))


(s/def ::transactions
  (s/coll-of (s/nilable (s/keys :req-un [::tx-hash]
                                :opt-un [::on-tx-receipt ::on-tx-success ::on-tx-error ::id]))))
(s/def ::watch-transactions (s/keys :req-un [::web3 ::transactions]))

(s/def ::addresses
  (s/coll-of (s/nilable (s/keys :req-un [::address ::on-success]
                                :opt-un [::on-error ::watch?]))))

(s/def ::get-balances (s/keys :req-un [::addresses ::web3]))


(s/def ::listen (s/keys :req-un [::on-success ::on-error ::web3]))


(defn- update-block [web3]
  (if @block-listener-init?
    (try
      (web3-eth/get-block-number web3 (fn [err block-number]
                                        (if err
                                          (js/setTimeout #(update-block web3) (* 5 default-delay))
                                          (do
                                            (reset! block-listener {:last-block block-number})
                                            (js/setTimeout #(update-block web3) default-delay)))))
      (catch :default e
        (js/console.error "Failed to fetch block number")
        (js/setTimeout #(update-block web3) (* 5 default-delay))))))


(defn- init-block-listener [web3]
  (update-block web3))


(defn- dispach-fn [on-success on-error & args]
  (fn [err res]
    (if err
      (dispatch (vec (concat on-error (cons err args))))
      (dispatch (vec (concat on-success (cons res args)))))))

(defn parse-event
  [event]
  (let [event (web3-utils/js->cljkk event)]
    (update event :return-values (fn [args]
                                   (reduce (fn [aggr next]
                                             (merge aggr {(keyword next) (aget args next)})) {} (js/Object.keys args))))))


(defn- contract-event-dispach-fn [on-success on-error]
  (fn [err res]
    (if err
      (dispatch (vec (concat on-error [err])))
        (let [event (parse-event res)
              event-args (:return-values event)]
          (dispatch (vec (concat on-success [event-args event])))))))


(defn- events-poll-handler
  [{:keys [:instance :event :event-filter-opts :block-filter-opts :callback]} event-last-block current-block]
      (let [from (if event-last-block (inc event-last-block) (:from-block block-filter-opts))
            ;; all events come in an array, unwrap them to call the callback for each of them
            callback-unwrapper (fn [err res]
                                 (if err
                                   (callback err res)
                                   (doseq [event res]
                                     (callback err event))))]
        (web3-eth/get-past-events instance
                                  event
                                  (merge {:filter event-filter-opts} block-filter-opts {:from-block from
                                                                                        :to-block current-block})
                                  callback-unwrapper)))


(defn- subscribe-events
  [{:keys [:instance :event :event-filter-opts :block-filter-opts :callback]}]
  (web3-eth/subscribe-events
    instance
    event
    (merge {:filter event-filter-opts} block-filter-opts)
    callback))


(defn- block-poll-handler
  [{:keys [:web3 :callback]} event-last-block current-block]
  (when (some? event-last-block)
    (doseq [block (map inc (range event-last-block current-block))]
      (web3-eth/get-block web3 block false callback))))


(defn- subscribe-block
  [{:keys [:web3 :callback]}]
    (web3-eth/subscribe-blocks web3 callback))


(defn- start-poll [block-process-fn {:keys [:web3 :instance :id :event :event-filter-opts :block-filter-opts :callback] :as args }]
  (when (compare-and-set! block-listener-init? false true)
    (init-block-listener web3))
  (js/setInterval
    (fn []
      (let [current-block (:last-block @block-listener)
            event-last-block (:block (get @*listeners* id))]
        (when (some? current-block)
          (when (or (nil? event-last-block) (> current-block event-last-block))
            (do
              (block-process-fn args event-last-block current-block)
              (swap! *listeners* assoc-in [id :block] current-block))))))
    default-delay))


(defn- stop-poll [interval]
  (js/clearInterval interval))


(defn- stop-listener! [id]
  (when-let [filter (get @*listeners* id)]
    (when (some? filter)
      (swap! *listeners* dissoc id)
      (when (empty? @*listeners*)
        (reset! block-listener-init? false))
      (if (= (:type filter) :poll)
        (stop-poll (:timer filter))
        (web3-eth/stop-watching! (:timer filter) (fn []))))))


(defn- start-listener! [subscribe-fn poll-fn {:keys [:id :web3 :callback] :as args}]
  (let [id (if id id callback)]
    (stop-listener! id)
    (->>
      (if (web3-core/support-subscriptions? web3)
        {:type :subscription
         :timer (subscribe-fn args)}
        {:type :poll
         :timer (start-poll poll-fn args)
         :block nil})
      (swap! *listeners* assoc id))
    id))


(defn- start-event-listener! [{:keys [:web3 :instance :id :event :event-filter-opts :block-filter-opts :callback] :as args}]
  (start-listener! subscribe-events events-poll-handler args))


(defn- start-block-listener! [{:keys [:web3 :id :callback] :as args}]
  (start-listener! subscribe-block block-poll-handler args))


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
  (fn [{:keys [:events :web3] :as params}]
    (s/assert ::watch-events params)
    (doseq [{:keys [:id :instance :block-filter-opts :event-filter-opts :on-success :on-error
                    :event]} events]
      (start-event-listener!
        {:web3 web3
         :instance instance
         :id id
         :event event
         :event-filter-opts event-filter-opts
         :block-filter-opts block-filter-opts
         :callback (contract-event-dispach-fn on-success on-error)}))))


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
  (fn [err res]
    (let [process-tx-receipt
          (fn [[err receipt]]
            (let [receipt (web3-utils/js->cljkk receipt)
                  hash-replaced? (every? nil? [err receipt])
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
          (let [block (web3-utils/js->cljkk (second (<! (web3-eth-async/get-block web3 (aget res "number") true))))
                txs  (:transactions block)
                target-tx (web3-utils/js->cljkk (second (<! (web3-eth-async/get-transaction web3 tx-hash))))
                speed-up-tx (first (filter  (fn [tx]
                                              (and
                                                (same-sender? tx target-tx)
                                                (not (same-hash? tx target-tx))
                                                (same-nonce? tx target-tx)
                                                )) txs))
                mined-hash (if speed-up-tx (:hash speed-up-tx) tx-hash)]
            (process-tx-receipt (<! (web3-eth-async/get-transaction-receipt web3 mined-hash)))))))))


(reg-fx
  :web3/watch-transactions
  (fn [{:keys [:web3 :transactions] :as params}]
    (s/assert ::watch-transactions params)
    (doseq [{:keys [:tx-hash :on-tx-receipt :on-tx-success :on-tx-error :id]} transactions]
      (let [listener-id (or id (rand 9999999))]
        (start-block-listener!
          {:web3 web3
           :id listener-id
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
            call-on-contract-instance #(apply web3-eth/contract-call (concat [instance fn] [args] [{}] [(dispach-fn on-success on-error)]))
            call-on-web3-instance #(apply fn (concat [web3] (when args args) [(dispach-fn on-success on-error)]))]
        (if call-contract-method? (call-on-contract-instance) (call-on-web3-instance))))))

(defn parse-receipt
  [tx-receipt]
  (let [tx-receipt (web3-utils/js->cljkk tx-receipt)]
    (update tx-receipt :events
            (fn [events]
              (into {} (for [[event-key event] events]
                         [event-key (parse-event event)]))))))

(defn safe-dispatch-one-many [re-event-one re-event-many result]
  (let [dispatch-single (fn [re-event result] (dispatch (conj (vec re-event) result)))]
    (when re-event-one (dispatch-single re-event-one result))
    (when re-event-many (doseq [re-event re-event-many] (dispatch-single re-event result)))))

(defn receipt-dispatch [{:keys [:on-tx-success :on-tx-success-n
                                :on-tx-receipt :on-tx-receipt-n]} tx-receipt]
  (let [tx-receipt (parse-receipt tx-receipt)]
    (safe-dispatch-one-many on-tx-receipt on-tx-receipt-n tx-receipt)
    (when (:status tx-receipt)
      (safe-dispatch-one-many on-tx-success on-tx-success-n tx-receipt))))

(defn error-dispatch [{:keys [:on-tx-hash-error :on-tx-hash-error-n
                              :on-tx-error :on-tx-error-n]} error tx-receipt]
  ; The difference between tx-error and tx-hash-error is whether the Error
  ; object passed to them has .receipt property (or in the second argument)
  ; https://web3js.readthedocs.io/en/v1.7.1/web3-eth-contract.html#id36
  (if tx-receipt
    (safe-dispatch-one-many on-tx-error on-tx-error-n (parse-receipt tx-receipt))
    (safe-dispatch-one-many on-tx-hash-error on-tx-hash-error-n (web3-utils/js->cljkk error))))

(defn dispatch-on-tx-promi-event [tx-promi-event {:keys [:on-tx-hash :on-tx-hash-n
                                                         :on-tx-hash-error :on-tx-hash-error-n
                                                         :on-tx-success :on-tx-success-n
                                                         :on-tx-receipt :on-tx-receipt-n
                                                         :on-tx-error :on-tx-error-n] :as re-events}]
  (.once tx-promi-event "transactionHash" (partial safe-dispatch-one-many on-tx-hash on-tx-hash-n))
  (.once tx-promi-event "receipt" (partial receipt-dispatch re-events))
  (.once tx-promi-event "error" (partial error-dispatch re-events)))

(def tx-result-re-events [:on-tx-hash :on-tx-hash-n
                          :on-tx-hash-error :on-tx-hash-error-n
                          :on-tx-success :on-tx-success-n
                          :on-tx-receipt :on-tx-receipt-n
                          :on-tx-error :on-tx-error-n])

(defn build-balance-id [address instance]
  (str "balance-" address (when instance (aget instance "options" "address"))))

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
          (web3-eth/contract-call instance method args {} (dispach-fn on-success on-error)))
        (apply method (concat [web3] args [(dispach-fn on-success on-error)]))))))

(reg-fx
  :web3/get-balances
  (fn [{:keys [:addresses :web3] :as params}]
    (s/assert ::get-balances params)
    (doseq [{:keys [:address :on-success :on-error :watch? :instance]} addresses]

      (if-not instance
        (web3-eth/get-balance web3 address (dispach-fn on-success on-error))
        (web3-eth/contract-call instance :balance-of [address] {} (dispach-fn on-success on-error)))


      (when (and watch? (seq addresses))
        (let [id (build-balance-id address instance)]
          (if-not instance
            (start-block-listener!
              {:web3 web3
               :id id
               :callback (fn [err]
                           (when-not err
                             (web3-eth/get-balance web3 address (dispach-fn on-success on-error))))})
            (do
              (start-event-listener!
                {:web3 web3
                 :instance instance
                 :id (str id "-from")
                 :event :Transfer
                 :event-filter-opts {:from address}
                 :block-filter-opts {:from-block "latest"}
                 :callback (fn []
                             (web3-eth/contract-call instance :balance-of [address] {} (dispach-fn on-success on-error)))})
              (start-event-listener!
                {:web3 web3
                 :instance instance
                 :id (str id "-to")
                 :event :Transfer
                 :event-filter-opts {:to address}
                 :block-filter-opts {:from-block "latest"}
                 :callback (fn []
                             (web3-eth/contract-call instance :balance-of [address] {} (dispach-fn on-success on-error)))}))))))))

(reg-fx
  :web3/stop-watching-balances
  (fn [{:keys [:addresses]}]
    (doseq [{:keys [:address :instance]} addresses]
      (let [id (build-balance-id address instance)]
        (if-not instance
          (stop-listener! id)
          (do
            (stop-listener! (str id "-from"))
            (stop-listener! (str id "-to"))))))))

(reg-fx
  :web3/watch-blocks
  (fn [{:keys [:web3 :id :on-success :on-error] :as config}]
    (s/assert ::listen config)
    (start-block-listener!
      {:web3 web3
       :id id
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
