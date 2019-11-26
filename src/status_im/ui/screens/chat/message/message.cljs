(ns status-im.ui.screens.chat.message.message
  (:require [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.utils.http :as http]
            [status-im.i18n :as i18n]
            [reagent.core :as reagent]
            [status-im.ui.components.colors :as colors]
            [status-im.utils.security :as security]
            [status-im.ui.components.icons.vector-icons :as vector-icons]
            [status-im.ui.components.list-selection :as list-selection]
            [status-im.ui.components.popup-menu.views :as desktop.pop-up]
            [status-im.ui.components.react :as react]
            [status-im.utils.money :as money]
            [status-im.ui.screens.chat.message.sheets :as sheets]
            [status-im.ui.screens.chat.photos :as photos]
            [status-im.ui.screens.chat.styles.message.message :as style]
            [status-im.ui.screens.chat.utils :as chat.utils]
            [status-im.utils.contenthash :as contenthash]
            [status-im.utils.platform :as platform])
  (:require-macros [status-im.utils.views :refer [defview letsubs]]))

(defview mention-element [from]
  (letsubs [{:keys [ens-name alias]} [:contacts/contact-name-by-identity from]]
    (str "@" (or ens-name alias))))

(defn message-timestamp
  [t justify-timestamp? outgoing content content-type]
  [react/text {:style (style/message-timestamp-text
                       justify-timestamp?
                       outgoing
                       (:rtl? content)
                       (= content-type constants/content-type-emoji))} t])

(defn message-view
  [{:keys [timestamp-str outgoing content content-type] :as message}
   message-content {:keys [justify-timestamp?]}]
  [react/view (style/message-view message)
   message-content
   [message-timestamp timestamp-str justify-timestamp? outgoing
    content content-type]])

(defview quoted-message
  [message-id {:keys [from text]} outgoing current-public-key]
  (letsubs [{:keys [quote ens-name alias]}
            [:messages/quote-info message-id]]
    (when (or quote text)
      [react/view {:style (style/quoted-message-container outgoing)}
       [react/view {:style style/quoted-message-author-container}
        [vector-icons/tiny-icon :tiny-icons/tiny-reply
         {:color (if outgoing colors/white-transparent colors/gray)}]
        (chat.utils/format-reply-author
         (or from (:from quote))
         alias ens-name current-public-key
         (partial style/quoted-message-author outgoing))]

       [react/text {:style           (style/quoted-message-text outgoing)
                    :number-of-lines 5}
        (or text (:text quote))]])))

(defn expand-button [expanded? chat-id message-id]
  [react/text {:style    style/message-expand-button
               :on-press #(re-frame/dispatch [:chat.ui/message-expand-toggled chat-id message-id])}
   (i18n/label (if expanded? :show-less :show-more))])

(defn render-inline [message-text outgoing acc {:keys [type literal destination] :as node}]
  (case type
    ""
    (conj acc literal)

    "code"
    (conj acc [react/text-class style/inline-code-style literal])

    "emph"
    (conj acc [react/text-class (style/emph-style outgoing) literal])

    "strong"
    (conj acc [react/text-class (style/strong-style outgoing) literal])

    "link"
    (conj acc
          [react/text-class
           {:style
            {:color (if outgoing colors/white colors/blue)
             :text-decoration-line :underline}
            :on-press
            #(when (and (security/safe-link? destination)
                        (security/safe-link-text? message-text))
               (if platform/desktop?
                 (.openURL react/linking (http/normalize-url destination))
                 (re-frame/dispatch
                  [:browser.ui/message-link-pressed destination])))}
           destination])

    "mention"
    (conj acc [react/text-class
               {:style {:color (if outgoing colors/mention-outgoing colors/mention-incoming)}
                :on-press
                #(re-frame/dispatch
                  [:chat.ui/start-chat literal {:navigation-reset? true}])}
               [mention-element literal]])

    "status-tag"
    (conj acc [react/text-class
               {:style {:color (if outgoing colors/white colors/blue)
                        :text-decoration-line :underline}
                :on-press
                #(re-frame/dispatch
                  [:chat.ui/start-public-chat literal {:navigation-reset? true}])}
               "#"
               literal])

    (conj acc literal)))

(defview message-content-status [{:keys [content]}]
  [react/view style/status-container
   [react/text {:style style/status-text}
    (reduce
     (fn [acc e] (render-inline (:text content) false acc e))
     [react/text-class {:style style/status-text}]
     (-> content :parsed-text peek :children))]])

(defn render-block [{:keys [chat-id message-id content
                            timestamp-str group-chat outgoing
                            current-public-key expanded?] :as message}
                    acc
                    {:keys [type literal children]}]
  (case type

    "paragraph"
    (conj acc (reduce
               (fn [acc e] (render-inline (:text content) outgoing acc e))
               [react/text-class (style/text-style outgoing)]
               children))

    "blockquote"
    (conj acc [react/view (style/blockquote-style outgoing)
               [react/text-class (style/blockquote-text-style outgoing)
                (.substring literal 0 (dec (.-length literal)))]])

    "codeblock"
    (conj acc [react/view style/codeblock-style
               [react/text-class style/codeblock-text-style
                (.substring literal 0 (dec (.-length literal)))]])

    acc))

(defn render-parsed-text [{:keys [timestamp-str
                                  outgoing] :as message}

                          tree]
  (let [elements (reduce (fn [acc e] (render-block message acc e)) [react/view {}] tree)
        timestamp [react/text {:style (style/message-timestamp-placeholder outgoing)}
                   (str "  " timestamp-str)]
        last-element (peek elements)]
      ;; Using `nth` here as slightly faster than `first`, roughly 30%
      ;; It's worth considering pure js structures for this code path as
      ;; it's perfomance critical
    (if (= react/text-class (nth last-element 0))
      ;; Append timestamp to last text
      (conj (pop elements) (conj last-element timestamp))
      ;; Append timestamp to new block
      (conj elements timestamp))))

(defn text-message
  [{:keys [chat-id message-id content
           timestamp-str group-chat outgoing current-public-key expanded?] :as message}]
  [message-view message
   (let [response-to (:response-to content)]
     [react/view
      (when (seq response-to)
        [quoted-message response-to (:quoted-message message) outgoing current-public-key])
      [render-parsed-text message (:parsed-text content)]])
   {:justify-timestamp? true}])

(defn emoji-message
  [{:keys [content current-public-key alias] :as message}]
  (let [response-to (:response-to content)]
    [message-view message
     [react/view {:style (style/style-message-text false)}
      (when response-to
        [quoted-message response-to (:quoted-message message) alias false current-public-key])
      [react/text {:style (style/emoji-message message)}
       (:text content)]]]))

(defmulti message-content (fn [_ message _] (message :content-type)))

(defmethod message-content constants/content-type-text
  [wrapper message]
  [wrapper message [text-message message]])

(defmethod message-content constants/content-type-status
  [wrapper message]
  [wrapper message [message-content-status message]])

(defmethod message-content constants/content-type-emoji
  [wrapper message]
  [wrapper message [emoji-message message]])

(defmethod message-content constants/content-type-sticker
  [wrapper {:keys [content] :as message}]
  [wrapper message
   [react/image {:style {:margin 10 :width 140 :height 140}
                 :source {:uri (contenthash/url (-> content :sticker :hash))}}]])

(defn- command-pending-status
  [command-state direction to]
  [react/view {:style {:flex-direction :row
                       :height 28
                       :align-items :center
                       :border-width 1
                       :border-color colors/gray-lighter
                       :border-radius 16
                       :padding-horizontal 8
                       :margin-right 12
                       :margin-bottom 2}}
   [vector-icons/icon :tiny-icons/tiny-pending
    {:width 16
     :height 16
     :color colors/gray
     :container-style {:margin-right 6}}]
   [react/text {:style {:color colors/gray
                        :font-weight "500"
                        :line-height 16
                        :margin-right 4
                        :font-size 13}}
    (if (and (= command-state :request-transaction)
             (= direction :incoming))
      (str (i18n/label :t/shared) " " to)
      (i18n/label (case command-state
                    :transaction-pending
                    :t/status-pending
                    :request-address-for-transaction
                    :t/address-requested
                    :request-transaction
                    :t/address-received)))]])

(defn- command-final-status
  [command-state]
  [react/view {:style {:flex-direction :row
                       :height 28
                       :align-items :center
                       :border-width 1
                       :border-color colors/gray-lighter
                       :border-radius 16
                       :padding-horizontal 8
                       :margin-right 12
                       :margin-bottom 2}}
   (case command-state
     (:request-address-for-transaction-declined
      :request-transaction-declined)
     [vector-icons/icon :tiny-icons/tiny-warning
      {:width 16
       :height 16
       :container-style {:margin-right 6}}]
     [vector-icons/icon :tiny-icons/tiny-check
      {:width 16
       :height 16
       :container-style {:margin-right 6}}])
   [react/text {:style {:font-weight "500"
                        :margin-right 4
                        :line-height 16
                        :font-size 13}}
    (i18n/label (case command-state
                  (:request-address-for-transaction-declined
                   :request-transaction-declined)
                  :t/transaction-declined
                  :transaction-sent
                  :t/status-confirmed))]])

(defn- command-status-and-timestamp
  [command-state direction to timestamp-str]
  [react/view {:style {:flex-direction :row
                       :justify-content :space-between}}
   (case command-state
     (:transaction-pending :request-transaction)
     [command-pending-status command-state direction to]
     :request-address-for-transaction
     (if (= direction :outgoing)
       [command-pending-status command-state direction to]
       [react/view])
     [command-final-status command-state])
   [react/text {:style {:font-size 10
                        :line-height 12
                        :text-align-vertical :bottom
                        :color colors/gray}}
    timestamp-str]])

(defn- command-actions
  [accept-label on-accept on-decline]
  [react/view
   [react/touchable-highlight
    {:style {:border-color colors/gray-lighter
             :border-top-width 1
             :margin-top 8
             :margin-horizontal -12
             :padding-horizontal 15
             :padding-vertical 10}}
    [react/text {:style {:text-align :center
                         :color colors/blue
                         :font-weight "500"
                         :font-size 15
                         :line-height 22}}
     (i18n/label accept-label)]]
   [react/touchable-highlight
    {:style {:border-color colors/gray-lighter
             :border-top-width 1
             :margin-horizontal -12
             :padding-top 10}}
    [react/text {:style {:text-align :center
                         :color colors/blue
                         :font-size 15
                         :line-height 22}}
     (i18n/label :t/decline)]]])

(defn- command-transaction-info
  [contract value]
  (let [{:keys [symbol icon decimals] :as token}
        (if contract
          (get @(re-frame/subscribe [:wallet/chain-tokens]) contract)
          @(re-frame/subscribe [:ethereum/native-currency]))
        amount (money/internal->formatted value symbol decimals)
        {:keys [code] :as currency}
        @(re-frame/subscribe [:wallet/currency])
        prices @(re-frame/subscribe [:prices])
        amount-fiat
        (money/fiat-amount-value amount symbol (keyword code) prices)]
    [react/view {:style {:flex-direction :row
                         :margin-top 8
                         :margin-bottom 12}}
     [react/image (-> icon
                      (update :source #(%))
                      (assoc-in [:style :height] 24)
                      (assoc-in [:style :width] 24))]
     [react/view {:style {:margin-left 6}}
      [react/text {:style {:margin-bottom 2
                           :font-size 20
                           :line-height 24}}
       (str amount " " (name symbol))]
      [react/text {:style {:font-size 12
                           :line-height 16
                           :color colors/gray}}
       (str amount-fiat " " code)]]]))

(defmethod message-content "command/transaction"
  [wrapper {:keys [content timestamp-str] :as message}]
  (let [{:keys [contract value to command-state direction]} content
        command-state (keyword command-state)
        direction :incoming #_(keyword direction)]
    [wrapper message
     [react/view {:padding-horizontal 12
                  :padding-bottom 10
                  :padding-top 10
                  :border-width 1
                  :border-color colors/gray-lighter
                  :border-radius      16
                  (if (= direction :outgoing)
                    :border-bottom-right-radius
                    :border-bottom-left-radius) 4
                  :background-color :white}
      [react/text {:style {:font-size 13
                           :line-height 18
                           :font-weight "500"
                           :color colors/gray}}
       (case direction
         :outgoing (str "↑ " (i18n/label :t/outgoing-transaction))
         :incoming (str "↓ " (i18n/label :t/incoming-transaction)))]
      [command-transaction-info contract value]
      [command-status-and-timestamp
       command-state direction to timestamp-str]
      (if (= direction :incoming)
        (when (= command-state :request-address-for-transaction)
          [command-actions
           :t/accept-and-share-address
           #() ;; TODO select account action
           #()])
        (when (= command-state :request-transaction)
          [command-actions
           :t/sign-and-send
           #() ;; TODO sign transaction action
           #()]))]]))

(defmethod message-content :default
  [wrapper {:keys [content-type] :as message}]
  [wrapper message
   [message-view message
    [react/text (str "Unhandled content-type " content-type)]]])

(defn message-activity-indicator
  []
  [react/view style/message-activity-indicator
   [react/activity-indicator {:animating true}]])

(defn message-not-sent-text
  [chat-id message-id]
  [react/touchable-highlight
   {:on-press
    (fn [] (if platform/desktop?
             (desktop.pop-up/show-desktop-menu
              (desktop.pop-up/get-message-menu-items chat-id message-id))
             (do
               (re-frame/dispatch
                [:bottom-sheet/show-sheet
                 {:content        (sheets/options chat-id message-id)
                  :content-height 200}])
               (react/dismiss-keyboard!))))}
   [react/view style/not-sent-view
    [react/text {:style style/not-sent-text}
     (i18n/label (if platform/desktop?
                   :t/status-not-sent-click
                   :t/status-not-sent-tap))]
    [react/view style/not-sent-icon
     [vector-icons/icon :main-icons/warning {:color colors/red}]]]])

(defn message-delivery-status
  [{:keys [chat-id message-id outgoing-status
           first-outgoing?
           content message-type] :as message}]
  (when (not= constants/message-type-private-group-system-message message-type)
    (case outgoing-status
      :sending  [message-activity-indicator]
      :not-sent [message-not-sent-text chat-id message-id]
      :sent     (when first-outgoing?
                  [react/view style/delivery-view
                   [react/text {:style style/delivery-text}
                    (i18n/label :t/status-sent)]])
      nil)))

(defview message-author-name [from alias]
  (letsubs [{:keys [ens-name]} [:contacts/contact-name-by-identity from]]
    (chat.utils/format-author alias style/message-author-name-container ens-name)))

(defn message-body
  [{:keys [alias
           last-in-group?
           first-in-group?
           display-photo?
           identicon
           display-username?
           from
           outgoing
           modal?
           content] :as message} child]
  [react/view (style/group-message-wrapper message)
   [react/view (style/message-body message)
    (when display-photo?
      [react/view (style/message-author outgoing)
       (when first-in-group?
         [react/touchable-highlight {:on-press #(when-not modal? (re-frame/dispatch [:chat.ui/show-profile from]))}
          [react/view
           [photos/member-photo from identicon]]])])
    [react/view (style/group-message-view outgoing display-photo?)
     (when display-username?
       [react/touchable-opacity {:on-press #(re-frame/dispatch [:chat.ui/show-profile from])}
        [message-author-name from alias]])
     [react/view {:style (style/timestamp-content-wrapper outgoing)}
      child]]]
   [react/view (style/delivery-status outgoing)
    [message-delivery-status message]]])

(defn open-chat-context-menu
  [{:keys [message-id content] :as message}]
  (list-selection/chat-message message-id (:text content) (i18n/label :t/message)))

(defn chat-message
  [{:keys [outgoing group-chat modal? current-public-key content-type content] :as message}]
  (let [sticker (:sticker content)]
    [react/view
     [react/touchable-highlight
      {:on-press      (fn [arg]
                        (if (and platform/desktop? (= "right" (.-button (.-nativeEvent arg))))
                          (open-chat-context-menu message)
                          (do
                            (when (and (= content-type constants/content-type-sticker) (:pack sticker))
                              (re-frame/dispatch [:stickers/open-sticker-pack (:pack sticker)]))
                            (re-frame/dispatch [:chat.ui/set-chat-ui-props {:messages-focused? true
                                                                            :input-bottom-sheet nil}])
                            (when-not platform/desktop?
                              (react/dismiss-keyboard!)))))
       :on-long-press #(when (or (= content-type constants/content-type-text)
                                 (= content-type constants/content-type-emoji))
                         (open-chat-context-menu message))}
      [react/view {:accessibility-label :chat-item}
       (let [incoming-group (and group-chat (not outgoing))]
         [message-content message-body (merge message
                                              {:current-public-key current-public-key
                                               :group-chat         group-chat
                                               :modal?             modal?
                                               :incoming-group     incoming-group})])]]]))
