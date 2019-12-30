(ns status-im.commands.core
  (:require
   [re-frame.core :as re-frame]
   [status-im.ethereum.json-rpc :as json-rpc]
   [status-im.utils.fx :as fx]))


(fx/defn handle-accept-request-address-for-transaction
  {:events [:commands/accept-request-address-for-transaction]}
  [cofx message-id]
  (let [address (get-in cofx [:db :multiaccount/accounts 0 :address])]
    {::json-rpc/call [{:method "shhext_acceptRequestAddressForTransaction"
                       :params [message-id address]
                       :on-success #(re-frame/dispatch [:transport/message-sent % 1])}]}))

(fx/defn handle-decline-request-address-for-transaction
  {:events [:commands/decline-request-address-for-transaction]}
  [cofx message-id]
  {::json-rpc/call [{:method "shhext_declineRequestAddressForTransaction"
                     :params [message-id]
                     :on-success #(re-frame/dispatch [:transport/message-sent % 1])}]})
