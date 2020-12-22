(ns re-frame-lint.utils)

(defn into!
  "Like into, but for transients"
  [to from]
  (reduce conj! to from))
