(ns status-im.chat.subs
  (:require [re-frame.core :refer [reg-sub dispatch subscribe path]]
            [status-im.data-store.chats :as chats]
            [status-im.chat.constants :as const]
            [status-im.chat.models.input :as input-model]
            [status-im.chat.utils :as chat-utils]
            [status-im.chat.views.input.utils :as input-utils]
            [status-im.constants :refer [response-suggesstion-resize-duration
                                         content-type-status
                                         console-chat-id]]
            [status-im.models.commands :as commands]
            [status-im.utils.platform :refer [platform-specific ios?]]
            [taoensso.timbre :as log]
            [clojure.string :as str]))

(reg-sub
  :chat-properties
  (fn [db [_ properties]]
    (->> properties
           (map (fn [k]
                  [k (get-in db[:chats (:current-chat-id db) k])
                         ]))
           (into {}))))

(reg-sub
  :chat-ui-props
  (fn [db [_ ui-element chat-id]]
    (let [current-chat-id (subscribe [:get-current-chat-id])]
      (get-in db [:chat-ui-props (or chat-id @current-chat-id) ui-element]))))

(reg-sub
  :chat-input-margin
  :<- [:get :keyboard-height]
  (fn [kb-height]
    (if ios? kb-height 0)))

(reg-sub
  :chat
  (fn [db [_ k chat-id]]
    (get-in db [:chats (or chat-id (:current-chat-id db)) k])))

(reg-sub
  :get-current-chat-id
  (fn [db ]
    (:current-chat-id db)))

(reg-sub
  :get-chat-by-id
  (fn [_ [_ chat-id]]
    (chats/get-by-id chat-id)))

(reg-sub :get-bots-suggestions
  (fn [db]
    (let [chat-id (subscribe [:get-current-chat-id])]
      (get-in db [:bots-suggestions @chat-id]))))

(reg-sub :get-commands
  (fn [db [_ chat-id]]
    (let [current-chat (or chat-id (db :current-chat-id))]
      (or (get-in db [:contacts current-chat :commands]) {}))))

(reg-sub
  :get-responses
  (fn [db [_ chat-id]]
    (let [current-chat (or chat-id (db :current-chat-id))]
      (or (get-in db [:contacts current-chat :responses]) {}))))

(reg-sub :get-commands-and-responses
  (fn [db [_ chat-id]]
    (let [{:keys [chats contacts]} db]
      (->> (get-in chats [chat-id :contacts])
           (filter :is-in-chat)
           (mapv (fn [{:keys [identity]}]
                   (let [{:keys [commands responses]} (get contacts identity)]
                     (merge responses commands))))
           (apply merge)))))

(reg-sub :possible-chat-actions
  (fn [db [_ chat-id]]
    "Returns a vector of [command message-id] values. `message-id` can be `:any`.
     Example: [[browse-command :any] [debug-command :any] [phone-command '1489161286111-58a2cd...']]"
    (let [chat-id (or chat-id (db :current-chat-id))]
      (input-model/possible-chat-actions db chat-id))))

(reg-sub
  :selected-chat-command
  (fn [db [_ chat-id]]
    (let [current-chat-id (subscribe [:get :current-chat-id])
          input-text      (subscribe [:chat :input-text])]
      (input-model/selected-chat-command db (or chat-id @current-chat-id) @input-text))))

(reg-sub
  :current-chat-argument-position
  (fn [db]
    (let [command       (subscribe [:selected-chat-command])
          input-text    (subscribe [:chat :input-text])
          seq-arguments (subscribe [:chat :seq-arguments])
          selection     (subscribe [:chat-ui-props :selection])]
      (input-model/current-chat-argument-position @command @input-text @selection @seq-arguments))))

(reg-sub
  :chat-parameter-box
  (fn [db]
    (let [chat-id (subscribe [:get-current-chat-id])
          command (subscribe [:selected-chat-command])
          index   (subscribe [:current-chat-argument-position])]
      (cond
        (and @command (not= @index input-model/*no-argument-error*))
        (let [command-name (get-in @command [:command :name])]
          (get-in db [:chats @chat-id :parameter-boxes command-name @index]))

        (not @command)
        (get-in db [:chats @chat-id :parameter-boxes :message])

        :default
        nil))))

(reg-sub
 :show-parameter-box?
  :<- [:chat-parameter-box]
  :<- [:show-suggestions?]
  :<- [:chat :input-text]
  :<- [:chat-ui-props :validation-messages]
  (fn [[chat-parameter-box show-suggestions? input-text validation-messages]]
    (and chat-parameter-box
         (not validation-messages)
         (not show-suggestions?))))

(reg-sub
  :command-completion
  (fn [db [_ chat-id]]
    (input-model/command-completion db chat-id)))

(reg-sub
  :show-suggestions?
  (fn [db [_ chat-id]]
    (let [chat-id           (or chat-id (db :current-chat-id))
          show-suggestions? (subscribe [:chat-ui-props :show-suggestions? chat-id])
          input-text        (subscribe [:chat :input-text chat-id])
          selected-command  (subscribe [:selected-chat-command chat-id])
          requests          (subscribe [:chat :request-suggestions chat-id])
          commands          (subscribe [:chat :command-suggestions chat-id])]
      (and (or @show-suggestions? (chat-utils/starts-as-command? (str/trim (or @input-text ""))))
           (not (:command @selected-command))
           (or (not-empty @requests)
               (not-empty @commands))))))

(reg-sub :get-current-chat
  (fn [db]
    (let [current-chat-id (:current-chat-id db)]
      (get-in db [:chats current-chat-id]))))

(reg-sub :get-chat
  (fn [db [_ chat-id]]
    (get-in db [:chats chat-id])))

(reg-sub :get-response
  (fn [db [_ n]]
    (let [chat-id (subscribe [:get-current-chat-id])]
      (get-in db [:contacts @chat-id :responses n]))))

(reg-sub :is-request-answered?
  :<- [:chat :requests]
  (fn [requests [_ message-id]]
    (not-any? #(= message-id (:message-id %)) requests)))

(reg-sub :unviewed-messages-count
  (fn [db [_ chat-id]]
    (get-in db [:unviewed-messages chat-id :count])))

(reg-sub :web-view-extra-js
  (fn [db]
    (let [chat-id (subscribe [:get-current-chat-id])]
      (get-in db [:web-view-extra-js @chat-id]))))

(reg-sub :all-messages-loaded?
  (fn [db]
    (let [chat-id (subscribe [:get-current-chat-id])]
      (get-in db [:chats @chat-id :all-loaded?]))))

(reg-sub :photo-path
  :<- [:get :contacts]
  (fn [contacts [_ id]]
    (:photo-path (contacts id))))

(reg-sub :get-last-message
  (fn [db [_ chat-id]]
    (let [{:keys [last-message messages]} (get-in db [:chats chat-id])]
      (->> (conj messages last-message)
        (sort-by :clock-value > )
             (filter :show?)
             (first)))))

(reg-sub :get-last-message-short-preview
  (fn [db [_ chat-id]]
    (let [last-message (subscribe [:get-last-message chat-id])]
      (get-in db [:message-data :short-preview (:message-id @last-message)]))))

(reg-sub :get-default-container-area-height
  :<- [:chat-ui-props :input-height]
  :<- [:get :layout-height]
  :<- [:chat-input-margin]
  (fn [[input-height layout-height chat-input-margin]]
    (let [bottom (+ input-height chat-input-margin)]
      (input-utils/default-container-area-height bottom layout-height))))

(reg-sub :get-max-container-area-height
  :<- [:chat-ui-props :input-height]
  :<- [:get :layout-height]
  :<- [:chat-input-margin]
  (fn [[input-height layout-height chat-input-margin]]
    (let [bottom (+ input-height chat-input-margin)]
      (input-utils/max-container-area-height bottom layout-height))))

(reg-sub :chat-animations
  (fn [db [_ key type]]
    (let [chat-id (subscribe [:get-current-chat-id])]
      (get-in db [:chat-animations @chat-id key type]))))

(reg-sub :get-chat-last-outgoing-message
  (fn [db [_ chat-id]]
    (->> (:messages (get-in db [:chats chat-id]))
         (filter :outgoing)
         (sort-by :clock-value >)
         (first))))
