(ns status-im.pairing.core
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [status-im.chat.models :as models.chat]
            [status-im.contact.core :as contact]
            [status-im.contact.db :as contact.db]
            [taoensso.timbre :as log]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.i18n :as i18n]
            [status-im.multiaccounts.model :as multiaccounts.model]
            [status-im.multiaccounts.update.core :as multiaccounts.update]
            [status-im.transport.message.pairing :as transport.pairing]
            [status-im.transport.message.protocol :as protocol]
            [status-im.ui.screens.navigation :as navigation]
            [status-im.utils.config :as config]
            [status-im.utils.fx :as fx]
            [status-im.utils.identicon :as identicon]
            [status-im.utils.pairing :as pairing.utils]
            [status-im.utils.platform :as utils.platform]
            [status-im.utils.types :as types]))

(defn enable-installation-rpc [installation-id on-success on-failure]
  (json-rpc/call {:method "shhext_enableInstallation"
                  :params [installation-id]
                  :on-success on-success
                  :on-failure on-failure}))

(defn disable-installation-rpc [installation-id on-success on-failure]
  (json-rpc/call {:method "shhext_disableInstallation"
                  :params [installation-id]
                  :on-success on-success
                  :on-failure on-failure}))

(defn set-installation-metadata-rpc [installation-id metadata on-success on-failure]
  (json-rpc/call {:method "shhext_setInstallationMetadata"
                  :params                 [installation-id metadata]
                  :on-success                 on-success
                  :on-failure                 on-failure}))

(defn get-our-installations-rpc [on-success on-failure]
  (json-rpc/call {:method "shhext_getOurInstallations"
                  :params  []
                  :on-success       on-success
                  :on-failure       on-failure}))

(def contact-batch-n 4)

(defn compare-installation
  "Sort installations, first by our installation-id, then on whether is
  enabled, and last on timestamp value"
  [our-installation-id a b]
  (cond
    (= our-installation-id (:installation-id a))
    -1
    (= our-installation-id (:installation-id b))
    1
    :else
    (let [enabled-compare (compare (:enabled? b)
                                   (:enabled? a))]
      (if (not= 0 enabled-compare)
        enabled-compare
        (compare (:timestamp b)
                 (:timestamp a))))))

(defn sort-installations
  [our-installation-id installations]
  (sort (partial compare-installation our-installation-id) installations))

(defn pair-installation [cofx]
  (let [installation-id (get-in cofx [:db :multiaccount :installation-id])
        installation-name (get-in cofx [:db :pairing/installations installation-id :name])
        device-type     utils.platform/os]
    (protocol/send (transport.pairing/PairInstallation. installation-id device-type installation-name nil) nil cofx)))

(defn send-pair-installation
  [cofx payload]
  (let [current-public-key (multiaccounts.model/current-public-key cofx)]
    {:shh/send-pairing-message {:src     current-public-key
                                :payload payload}}))

(defn merge-contact [local remote]
  ;;TODO we don't sync contact/blocked for now, it requires more complex handling
  (let [remove (update remote :system-tags disj :contact/blocked)
        [old-contact new-contact] (sort-by :last-updated [remote local])]
    (-> local
        (merge new-contact)
        (assoc ;; we only take system tags from the newest contact version
         :system-tags  (:system-tags new-contact)))))

(def merge-contacts (partial merge-with merge-contact))

(def multiaccount-mergeable-keys [:name :photo-path :last-updated])

(defn merge-multiaccount [local remote]
  (if (> (:last-updated remote) (:last-updated local))
    (merge local (select-keys remote multiaccount-mergeable-keys))
    local))

(fx/defn prompt-dismissed [{:keys [db]}]
  {:db (assoc-in db [:pairing/prompt-user-pop-up] false)})

(fx/defn prompt-accepted [{:keys [db] :as cofx}]
  (fx/merge cofx
            {:db (assoc-in db [:pairing/prompt-user-pop-up] false)}
            (navigation/navigate-to-cofx :installations nil)))

(fx/defn prompt-user-on-new-installation [{:keys [db]}]
  (when-not config/pairing-popup-disabled?
    {:db               (assoc-in db [:pairing/prompt-user-pop-up] true)
     :ui/show-confirmation {:title      (i18n/label :t/pairing-new-installation-detected-title)
                            :content    (i18n/label :t/pairing-new-installation-detected-content)
                            :confirm-button-text (i18n/label :t/pairing-go-to-installation)
                            :cancel-button-text  (i18n/label :t/cancel)
                            :on-cancel  #(re-frame/dispatch [:pairing.ui/prompt-dismissed])
                            :on-accept #(re-frame/dispatch [:pairing.ui/prompt-accepted])}}))

(fx/defn set-name
  "Set the name of the device"
  [{:keys [db] :as cofx} installation-name]
  (let [our-installation-id (get-in db [:multiaccount :installation-id])]
    {:pairing/set-installation-metadata [[our-installation-id {:name installation-name
                                                               :deviceType utils.platform/os}]]}))

(fx/defn init [cofx]
  {:pairing/get-our-installations nil})

(defn handle-bundles-added [{:keys [db] :as cofx} bundle]
  (let [installation-id  (:installationID bundle)]
    (when
     (and (= (:identity bundle)
             (multiaccounts.model/current-public-key cofx))
          (not= (get-in db [:multiaccount :installation-id]) installation-id))
      (fx/merge cofx
                (init)
                #(when-not (or (:pairing/prompt-user-pop-up db)
                               (= :installations (:view-id db)))
                   (prompt-user-on-new-installation %))))))

(defn sync-installation-multiaccount-message [{:keys [db]}]
  (let [multiaccount (-> db
                         :multiaccount
                         (select-keys multiaccount-mergeable-keys))]
    (transport.pairing/SyncInstallation. {} multiaccount {})))

(defn- contact->pairing [contact]
  (cond-> (-> contact
              (dissoc :photo-path)
              (update :system-tags disj :contact/blocked))
    ;; for compatibility with version < contact.v7
    (contact.db/added? contact) (assoc :pending? false)
    (contact.db/legacy-pending? contact) (assoc :pending? true)))

(defn- contact-batch->sync-installation-message [batch]
  (let [contacts-to-sync
        (reduce (fn [acc {:keys [public-key system-tags] :as contact}]
                  (assoc acc
                         public-key
                         (contact->pairing contact)))
                {}
                batch)]
    (transport.pairing/SyncInstallation. contacts-to-sync {} {})))

(defn- chats->sync-installation-messages [{:keys [db]}]
  (->> db
       :chats
       vals
       (filter :public?)
       (filter :is-active)
       (map #(select-keys % [:chat-id :public?]))
       (map #(transport.pairing/SyncInstallation. {} {} %))))

(defn sync-installation-messages [{:keys [db] :as cofx}]
  (let [contacts (contact.db/get-active-contacts (:contacts/contacts db))
        contact-batches (partition-all contact-batch-n contacts)]
    (concat (mapv contact-batch->sync-installation-message contact-batches)
            [(sync-installation-multiaccount-message cofx)]
            (chats->sync-installation-messages cofx))))

(fx/defn enable [{:keys [db]} installation-id]
  {:db (assoc-in db
                 [:pairing/installations installation-id :enabled?]
                 true)})

(fx/defn disable [{:keys [db]} installation-id]
  {:db (assoc-in db
                 [:pairing/installations installation-id :enabled?]
                 false)})

(defn handle-enable-installation-response-success
  "Callback to dispatch on enable signature response"
  [installation-id]
  (re-frame/dispatch [:pairing.callback/enable-installation-success installation-id]))

(defn handle-disable-installation-response-success
  "Callback to dispatch on disable signature response"
  [installation-id]
  (re-frame/dispatch [:pairing.callback/disable-installation-success installation-id]))

(defn handle-set-installation-metadata-response-success
  "Callback to dispatch on set-installation-metadata response"
  [installation-id metadata]
  (re-frame/dispatch [:pairing.callback/set-installation-metadata-success installation-id metadata]))

(defn handle-get-our-installations-response-success
  "Callback to dispatch on get-our-installation response"
  [result]
  (re-frame/dispatch [:pairing.callback/get-our-installations-success result]))

(defn enable-installation! [installation-id]
  (enable-installation-rpc installation-id
                           (partial handle-enable-installation-response-success installation-id)
                           nil))

(defn disable-installation! [installation-id]
  (disable-installation-rpc installation-id
                            (partial handle-disable-installation-response-success installation-id)
                            nil))

(defn set-installation-metadata! [installation-id metadata]
  (set-installation-metadata-rpc installation-id
                                 metadata
                                 (partial handle-set-installation-metadata-response-success installation-id metadata)
                                 nil))

(defn get-our-installations []
  (get-our-installations-rpc handle-get-our-installations-response-success nil))

(defn enable-fx [cofx installation-id]
  (if (< (count (filter :enabled? (vals (get-in cofx [:db :pairing/installations])))) (inc config/max-installations))
    {:pairing/enable-installation installation-id}
    {:utils/show-popup {:title (i18n/label :t/pairing-maximum-number-reached-title)

                        :content (i18n/label :t/pairing-maximum-number-reached-content)}}))

(defn disable-fx [_ installation-id]
  {:pairing/disable-installation installation-id})

(re-frame/reg-fx
 :pairing/enable-installation
 enable-installation!)

(re-frame/reg-fx
 :pairing/disable-installation
 disable-installation!)

(re-frame/reg-fx
 :pairing/set-installation-metadata
 (fn [pairs]
   (doseq [[installation-id metadata] pairs]
     (set-installation-metadata! installation-id metadata))))

(re-frame/reg-fx
 :pairing/get-our-installations
 get-our-installations)

(fx/defn send-sync-installation
  [cofx payload]
  (let [current-public-key (multiaccounts.model/current-public-key cofx)]
    {:shh/send-direct-message
     [{:src     current-public-key
       :dst     current-public-key
       :payload payload}]}))

(fx/defn send-installation-message-fx [cofx payload]
  (when (pairing.utils/has-paired-installations? cofx)
    (protocol/send payload nil cofx)))

(fx/defn sync-public-chat [cofx chat-id]
  (let [sync-message (transport.pairing/SyncInstallation. {} {} {:public? true
                                                                 :chat-id chat-id})]
    (send-installation-message-fx cofx sync-message)))

(fx/defn sync-contact
  [cofx {:keys [public-key] :as contact}]
  (let [sync-message (transport.pairing/SyncInstallation.
                      {public-key (cond-> contact
                                    ;; for compatibility with version < contact.v7
                                    (contact.db/added? contact) (assoc :pending? false)
                                    (contact.db/legacy-pending? contact) (assoc :pending? true))}
                      {} {})]
    (send-installation-message-fx cofx sync-message)))

(defn send-installation-messages [cofx]
  ;; The message needs to be broken up in chunks as we hit the whisper size limit
  (let [sync-messages (sync-installation-messages cofx)
        sync-messages-fx (map send-installation-message-fx sync-messages)]
    (apply fx/merge cofx sync-messages-fx)))

(defn ensure-photo-path
  "Make sure a photo path is there, generate otherwise"
  [contacts]
  (reduce-kv (fn [acc k {:keys [public-key photo-path] :as v}]
               (assoc acc k
                      (assoc
                       v
                       :photo-path
                       (if (string/blank? photo-path)
                         (identicon/identicon public-key)
                         photo-path))))
             {}
             contacts))

(defn ensure-system-tags
  "Make sure system tags is there"
  [contacts]
  (reduce-kv (fn [acc k {:keys [system-tags] :as v}]
               (assoc acc k
                      (assoc
                       v
                       :system-tags
                       (if system-tags
                         system-tags
                         (if (and (contains? v :pending?) (not (:pending? v)))
                           #{:contact/added}
                           #{:contact/request-received})))))
             {}
             contacts))

(defn handle-sync-installation
  [{:keys [db] :as cofx} {:keys [contacts account chat]} sender]
  (let [confirmation  (:metadata cofx)]
    (when (= sender (multiaccounts.model/current-public-key cofx))
      (let [new-contacts  (when (seq contacts)
                            (vals (merge-contacts (:contacts/contacts db)
                                                  ((comp ensure-photo-path
                                                         ensure-system-tags) contacts))))
            {old-name :name
             old-photo-path :photo-path
             old-last-updated :last-updated
             :as multiaccount} (:multiaccount db)
            {:keys [name photo-path last-updated]}
            (merge-multiaccount multiaccount account)
            contacts-fx (when new-contacts
                          (mapv contact/upsert-contact new-contacts))]
        (apply fx/merge
               cofx
               (concat
                [{:db (-> db
                          (assoc-in [:multiaccount :name] name)
                          (assoc-in [:multiaccount :last-updated] last-updated)
                          (assoc-in [:multiaccount :photo-path] photo-path))
                  ::json-rpc/call
                  [(when (not= old-name name)
                     {:method "settings_saveConfig"
                      :params [:name name]
                      :on-success #(log/debug "handled sync of name field successfully")})
                   (when (not= old-photo-path photo-path)
                     {:method "settings_saveConfig"
                      :params [:photo-path photo-path]
                      :on-success #(log/debug "handled sync of photo-path field successfully")})
                   (when (not= old-last-updated last-updated)
                     {:method "settings_saveConfig"
                      :params [:last-updated last-updated]
                      :on-success #(log/debug "handled sync of last-updated field successfully")})]}
                 #(when (:public? chat)
                    (models.chat/start-public-chat % (:chat-id chat) {:dont-navigate? true}))]
                contacts-fx))))))

(defn handle-pair-installation
  [{:keys [db] :as cofx} {:keys [name installation-id
                                 device-type]} timestamp sender]
  (when (and (= sender (multiaccounts.model/current-public-key cofx))
             (not= (get-in db [:multiaccount :installation-id]) installation-id))
    {:pairing/set-installation-metadata [[installation-id {:name name
                                                           :deviceType device-type}]]}))

(fx/defn update-installation [{:keys [db]} installation-id metadata]
  {:db (update-in db [:pairing/installations installation-id]
                  assoc
                  :installation-id installation-id
                  :name (:name metadata)
                  :device-type (:deviceType metadata))})

(fx/defn load-installations [{:keys [db]} installations]
  {:db (assoc db :pairing/installations (reduce
                                         (fn [acc {:keys [metadata id enabled] :as i}]
                                           (assoc acc id
                                                  {:installation-id id
                                                   :name (:name metadata)
                                                   :timestamp (:timestamp metadata)
                                                   :device-type (:deviceType metadata)
                                                   :enabled? enabled}))
                                         {}
                                         installations))})
