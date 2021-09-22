(ns re-frame-lint.lints
  (:require [re-frame-lint.utils :as utils]
            [clojure.string :as cs]))

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

(defn- filter-private-keys
  ":key 가 _ 로 시작되는 애들을 찾음."
  [coll]
  (filter (fn [{:keys [key]}]
            (-> (name key)
                (cs/starts-with? "_")))
          coll))

(defn- filter-public-keys
  ":key 가 _ 로 시작되는 애들 제외"
  [coll]
  (filter (fn [{:keys [key]}]
            (-> (name key)
                (cs/starts-with? "_")
                (not)))
          coll))

(defn- filter-used-from-other-ns
  ":ns와 :key의 namespace가 일치하지 않는 값들"
  [coll]
  (filter (fn [info]
            (let [used-in-ns (name (:ns info))
                  decl-in-ns (namespace (:key info))]
              (not= used-in-ns
                    decl-in-ns)))
          coll))

(defn- find-misused-private-key
  ":ns 와 :key의 namespace가 일치하지 않는 private keyword들 찾음."
  [refer-keys]
  (->> refer-keys
       (filter-private-keys)
       (filter-used-from-other-ns)))

(defn lint-misused-private-sub-keys
  "namespace 밖에서 쓰인 private sub 키들 찾음."
  [call-info]
  (let [errors (find-misused-private-key (:refer-sub-key call-info))]
    (when (seq errors)
      (prn "Private sub keys used out of namespace:")
      (print-infos "warning"
                   "misused-private-sub-key"
                   errors)
      true)))

(defn lint-misused-private-event-keys
  "namespace 밖에서 쓰인 private event 키들 찾음."
  [call-info]
  (let [errors (find-misused-private-key (:refer-event-key call-info))]
    (when (seq errors)
      (prn "Private event keys used out of namespace:")
      (print-infos "warning"
                   "misused-private-event-key"
                   errors)
      true)))

(defn- find-unused-outside-key [decl-info refer-info]
  (let [used-keys (->> refer-info
                       (filter-used-from-other-ns)
                       (map :key)
                       (set))]
    (->> decl-info
         (filter-public-keys)
         (filter (comp not used-keys :key)))))

(defn lint-should-private-sub-keys
  "namespace 밖에서 쓰이지 않는 sub키는 private로 선언하도록."
  [call-info]
  (let [errors (find-unused-outside-key (:decl-sub-key call-info)
                                        (:refer-sub-key call-info))]
    (when (seq errors)
      (prn "Not used out of namespace. Consider switch to private keyword:")
      (print-infos "warning"
                   "switch-to-private-sub-key"
                   errors)
      true)))

(defn lint-should-private-event-keys
  "namespace 밖에서 쓰이지 않는 event키는 private로 선언하도록."
  [call-info]
  (let [errors (find-unused-outside-key (:decl-event-key call-info)
                                        (:refer-event-key call-info))]
    (when (seq errors)
      (prn "Not used out of namespace. Consider switch to private keyword:")
      (print-infos "warning"
                   "switch-to-private-event-key"
                   errors)
      true)))

(defn- find-invalid-fx-key [decl-info]
  (->> decl-info
       (filter (fn [{:keys [key]}]
                 (-> key
                     (utils/fx-keyword?)
                     (not))))))

(defn lint-invalid-fx-keys
  "fx-key는 ns-qualified에 fx- 를 prefix로 쓴다."
  [call-info]
  (let [errors (find-invalid-fx-key (:decl-fx-key call-info))]
    (when (seq errors)
      (prn (str "Invalid fx name. Add prefix " \" utils/fx-key-prefix \"))
      (print-infos "warning"
                   "invalid-fx-key"
                   errors)
      true)))

(defn- find-unknown-fx-key [call-info]
  (let [registered-fx-keys (->> (:decl-fx-key call-info)
                                 (map :key)
                                 (set))]
    (->> (:refer-fx-key call-info)
         (filter (comp not registered-fx-keys :key)))))

(defn lint-unknown-fx-keys [call-info]
  (let [unknown-keys (find-unknown-fx-key call-info)]
    (when (seq unknown-keys)
      (prn "Unknown fx keys:")
      (print-infos "error"
                   "unknown-fx-key"
                   unknown-keys)
      true)))

(defn- find-unused-fx-key [call-info]
  (let [used-keys (->> (:refer-fx-key call-info)
                       (map :key)
                       (set))]
    (->> (:decl-fx-key call-info)
         ;; 이건 invalid-fx-key 에서 보고될 예정
         (filter (comp utils/fx-keyword? :key))
         (filter (comp not used-keys :key)))))

(defn lint-unused-fx-keys [call-info]
  (let [unused-keys (find-unused-fx-key call-info)]
    (when (seq unused-keys)
      (prn "Unusued fx keys:")
      (print-infos "warning"
                   "unused-fx-key"
                   unused-keys)
      true)))
