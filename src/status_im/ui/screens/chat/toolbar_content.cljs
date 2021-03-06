(ns status-im.ui.screens.chat.toolbar-content
  (:require [status-im.i18n :as i18n]
            [status-im.ui.components.chat-icon.screen :as chat-icon.screen]
            [status-im.ui.components.react :as react]
            [status-im.ui.screens.chat.styles.main :as st])
  (:require-macros [status-im.utils.views :refer [defview letsubs]]))

(defn- in-progress-text [{:keys [highestBlock currentBlock startBlock]}]
  (let [total      (- highestBlock startBlock)
        ready      (- currentBlock startBlock)
        percentage (if (zero? ready)
                     0
                     (->> (/ ready total)
                          (* 100)
                          (.round js/Math)))]

    (str (i18n/label :t/sync-in-progress) " " percentage "% " currentBlock)))

(defview last-activity [{:keys [sync-state accessibility-label]}]
  (letsubs [state [:sync-data]]
    [react/text {:style               st/last-activity-text
                 :accessibility-label accessibility-label}
     (case sync-state
       :in-progress (in-progress-text state)
       :synced      (i18n/label :t/sync-synced))]))

(defn- group-last-activity [{:keys [contacts sync-state public?]}]
  (if (or (= sync-state :in-progress)
          (= sync-state :synced))
    [last-activity {:sync-state sync-state}]
    [react/view {:flex-direction :row}
     [react/text {:style st/toolbar-subtitle}
      (if public?
        (i18n/label :t/public-group-status)
        (let [cnt (count contacts)]
          (if (zero? cnt)
            (i18n/label :members-active-none)
            (i18n/label-pluralize cnt :t/members-active))))]]))

(defn- contact-indicator [{:keys [added?]}]
  [react/view {:flex-direction :row}
   [react/text {:style st/toolbar-subtitle}
    (if added?
      (i18n/label :chat-is-a-contact)
      (i18n/label :chat-is-not-a-contact))]])

(defview toolbar-content-view []
  (letsubs [{:keys [group-chat color online contacts chat-name contact public?]}
            [:chats/current-chat]
            sync-state [:sync-state]]
    (let [has-subtitle? (or group-chat (not= :done sync-state))]
      [react/view {:style st/toolbar-container}
       [react/view {:margin-right 10}
        [chat-icon.screen/chat-icon-view-toolbar contact group-chat chat-name color online]]
       [react/view {:style st/chat-name-view}
        [react/text {:style               st/chat-name-text
                     :number-of-lines     1
                     :accessibility-label :chat-name-text}
         chat-name]
        (when contact
          [contact-indicator contact])
        (if group-chat
          [group-last-activity {:contacts   contacts
                                :public?    public?
                                :sync-state sync-state}]
          (when has-subtitle?
            [last-activity {:sync-state          sync-state
                            :accessibility-label :last-seen-text}]))]])))
