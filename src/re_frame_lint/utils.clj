(ns re-frame-lint.utils)

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
