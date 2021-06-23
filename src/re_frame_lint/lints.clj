(ns re-frame-lint.lints
  (:require [re-frame-lint.utils :as utils]))

(defn- refer-info->str
  "clj-kondo style과 비슷하게 맞춤."
  [level linter-name info]
  (str (:filepath info)
       ":"
       (:line info)
       ":"
       (:column info)
       ": "
       level
       ": "
       linter-name
       ": "
       (:key info)))

(defn- print-info [level linter-name info]
  (println (refer-info->str level
                            linter-name
                            info)))

(defn- print-infos [level linter-name infos]
  (doseq [info infos]
    (print-info level
                linter-name
                info))
  (println ""))

(defn- find-unknown-sub-key [call-info]
  (let [registered-sub-keys (->> (:decl-sub-key call-info)
                                 (map :key)
                                 (set))]
    (->> (:refer-sub-key call-info)
         (filter (comp not registered-sub-keys :key)))))

(defn lint-unknown-sub-keys [call-info]
  (let [unknown-keys (find-unknown-sub-key call-info)]
    (when (seq unknown-keys)
      (prn "Unknown sub keys:")
      (print-infos "error"
                   "unknown-sub-key"
                   unknown-keys)
      true)))

(defn- find-unknown-event-key [call-info]
  (let [registered-event-key (->> (:decl-event-key call-info)
                                  (map :key)
                                  (set))]
    (->> (:refer-event-key call-info)
         (filter (comp not registered-event-key :key)))))

(defn lint-unknown-event-keys [call-info]
  (let [unknown-keys (find-unknown-event-key call-info)]
      (when (seq unknown-keys)
        (prn "Unknown event keys:")
        (print-infos "error"
                     "unknown-event-key"
                     unknown-keys)
        true)))

(defn- find-unused-sub-key [call-info]
  (let [used-keys (->> (:refer-sub-key call-info)
                          (map :key)
                          (set))]
    (->> (:decl-sub-key call-info)
         (filter (comp not used-keys :key)))))

(defn lint-unused-sub-keys [call-info]
  (let [unused-keys (find-unused-sub-key call-info)]
    (when (seq unused-keys)
      (prn "Unusued sub keys:")
      (print-infos "warning"
                   "unused-sub-key"
                   unused-keys)
      true)))

(defn- find-unused-event-key [call-info]
  (let [used-keys (->> (:refer-event-key call-info)
                       (map :key)
                       (set))]
    (->> (:decl-event-key call-info)
         (filter (comp not used-keys :key)))))

(defn lint-unused-event-keys [call-info]
  (let [unused-keys (find-unused-event-key call-info)]
    (when (seq unused-keys)
      (prn "Unused event keys:")
      (print-infos "warning"
                   "unused-event-key"
                   unused-keys)
      true)))

(defn- find-signal-args-mismatch [call-info]
  (let [decl-subs (:decl-sub-key call-info)]
    (->> decl-subs
         (filter (comp vector? first :spec))
         (filter (fn [decl-sub-info]
                   (let [spec-form (-> decl-sub-info
                                       :spec
                                       first)
                         arity (if (= (second (reverse spec-form))
                                      :as)
                                 (- (count spec-form) 2)
                                 (count spec-form))
                         signal-arity (:input-signal-count decl-sub-info)]
                     (not= signal-arity
                           arity)))))))

(defn lint-signal-args-mismatch [call-info]
  (let [mismatchs (find-signal-args-mismatch call-info)]
    (when (seq mismatchs)
      (prn "signal args mismatch:")
      (print-infos "error"
                   "signal-args-mismatch"
                   mismatchs)
      true)))

(defn- find-arity-mismatch [decls refers]
  (let [decl-map (utils/to-map decls
                               :key)]
    (keep (fn [refer-info]
              (let [invoke-arity (count (:args refer-info))
                    decl-arity (get-in decl-map
                                       [(:key refer-info)
                                        :arity])]
                (when (and decl-arity
                           (not= decl-arity
                                 invoke-arity))
                  {:refer refer-info
                   :invoke-arity invoke-arity
                   :decl-arity decl-arity})))
            refers)))

(defn- print-mismatch-infos [level linter-name infos]
  (doseq [info infos]
    (let [refer-info (refer-info->str level
                                      linter-name
                                      (:refer info))]
      (println refer-info
               (str "expected: "
                    (:decl-arity info)
                    ", got: "
                    (:invoke-arity info)))))
  (println ""))

(defn lint-subs-arity-mismatch [call-info]
  (let [mismatchs (find-arity-mismatch (:decl-sub-key call-info)
                                       (:refer-sub-key call-info))]
    (when (seq mismatchs)
      (prn "subscribe call arity mismatch:")
      (print-mismatch-infos "error"
                            "sub-call-arity-mismatch"
                            mismatchs)
      true)))

(defn lint-event-arity-mismatch [call-info]
  (let [mismatchs (find-arity-mismatch (:decl-event-key call-info)
                                       (:refer-event-key call-info))]
    (when (seq mismatchs)
      (prn "event call arity mismatch:")
      (print-mismatch-infos "error"
                            "event-call-arity-mismatch"
                            mismatchs)
      true)))
