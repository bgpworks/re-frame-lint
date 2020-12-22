(ns test-data.analyze-me
  (:require [re-frame.core :as rf]
            [test-data.subs :as subs]))

(rf/reg-sub
 ::setting
 :<- [::local-db]
 (fn [ldb _]
   ldb))

(def ns-keyword-symbol ::ns-keyword)

(defn top []
  (let [state_ (rf/subscribe [::subs/subs-key])]
    (fn []
      [:div
       @state_])))
