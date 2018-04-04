(ns district0x.re-frame.web3-fx
  (:require
    [cljs-web3.eth :as web3-eth]
    [cljs.spec.alpha :as s]
    [re-frame.core :refer [reg-fx dispatch console reg-event-db reg-event-fx]]))

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


(defn- dispatch-on-tx-receipt-fn [{:keys [:on-tx-success :on-tx-receipt :on-tx-error :tx-hash :web3 :id]}]
  (fn [err]
    (when-not err
      (web3-eth/get-transaction-receipt
        web3
        tx-hash
        (fn [_ receipt]
          (when (:block-number receipt)
            (stop-listener! id)
            (when on-tx-receipt
              (dispatch (conj (vec on-tx-receipt) receipt)))
            (condp #(contains? %1 %2) (:status receipt)
              #{"0x0" "0x00" 0} (when on-tx-error
                                  (dispatch (conj (vec on-tx-error) receipt)))
              #{"0x1" "0x01" 1} (when on-tx-success
                                  (dispatch (conj (vec on-tx-success) receipt))))))))))


(defn- contract-state-call-callback [{:keys [:web3 :on-tx-hash :on-tx-hash-error :on-tx-receipt :on-tx-error
                                             :on-tx-success]}]
  (fn [err tx-hash]
    (if err
      (when on-tx-hash-error
        (dispatch (conj (vec on-tx-hash-error) err)))
      (let [listener-id (rand 9999999)]
        (when on-tx-hash
          (dispatch (conj (vec on-tx-hash) tx-hash)))
        (start-listener!
          {:web3 web3
           :id listener-id
           :block-filter-opts "latest"
           :callback (dispatch-on-tx-receipt-fn
                       {:id listener-id
                        :on-tx-receipt on-tx-receipt
                        :on-tx-error on-tx-error
                        :on-tx-success on-tx-success
                        :tx-hash tx-hash
                        :web3 web3})})))))


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
    (doseq [{:keys [:fn :instance :args :tx-opts :on-success :on-error :on-tx-hash :on-tx-hash-error
                    :on-tx-receipt :on-tx-error :on-tx-success]} (remove nil? fns)]
      (if instance
        (if tx-opts
          (apply web3-eth/contract-call
                 (concat [instance fn]
                         args
                         [tx-opts]
                         [(contract-state-call-callback
                            {:web3 web3
                             :on-tx-hash on-tx-hash
                             :on-tx-hash-error on-tx-hash-error
                             :on-tx-receipt on-tx-receipt
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
