(ns test-data.analyze-me
  (:require [re-frame.core :as rf]
            [test-data.subs :as subs]))

(rf/reg-sub
 ::setting
 :<- [::local-db]
 (fn [ldb _]
   (:a ldb)))

(rf/reg-sub
 ::omit-param-sub
 (fn [db]
   (let [db (:inner db)]
     (:a db))))

(rf/reg-sub
 ::parameterized-sub
 (fn [db [_ hello]]
   (str (:a db) hello)))

(rf/reg-sub
 ::complex-parameterized-sub
 :<- [::input-a]
 :<- [::input-b]
 (fn [[input-a input-b] [_ arg]]
   (let [max-input (max input-a input-b)]
     (str max-input
          arg))))

(defn- ^:reg-event-fx call-last-res-id []
  {:url "/latest-res-id"
   :method "GET"
   :on-success [::check-latest-res-id-success]
   :on-error [::check-latest-res-id-error]
   :on-complete [::check-latest-res-id-complete]
   :on-finally [::check-latest-res-id-finally]})

(rf/reg-event-fx
 ::update-setting
 (fn [cofx _]
   {::api (call-last-res-id)
    :dispatch [::product-edit 1]
    :dispatch-n (list [::dispatch-n-1]
                      [::dispatch-n-2 2]
                      [::dispatch-n-3 :arg])}))

(def ns-keyword-symbol ::ns-keyword)

(defn top []
  (let [state_ (rf/subscribe [::subs/subs-key])]
    (fn []
      [:div
       @state_])))
