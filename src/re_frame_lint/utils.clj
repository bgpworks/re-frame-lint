(ns re-frame-lint.utils
  (:require [clojure.string :as string]))

(def fx-key-prefix "fx-")

(defn into!
  "Like into, but for transients"
  [to from]
  (reduce conj! to from))

(defn to-map
  "list를 받아서 (key-fn item) => item 맵으로 만듬.
  또는 (key-fn item) => (val-fn item) 맵으로 만듬."
  ([call key-fn]
   (to-map call key-fn identity))
  ([coll key-fn val-fn]
   (persistent!
    (reduce (fn [aux item]
              (assoc! aux (key-fn item) (val-fn item)))
            (transient {})
            coll))))

(defn fx-keyword? [fx-key]
  (and (keyword? fx-key)
       (qualified-keyword? fx-key)
       (string/starts-with? (name fx-key)
                            fx-key-prefix)))
