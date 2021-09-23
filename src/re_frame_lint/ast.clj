(ns re-frame-lint.ast
  (:require [re-frame-lint.ast :as ast]
            [cljs.analyzer.api :as ana-api]
            [cljs.analyzer :as cljs-ana]
            [cljs.env :as cljs-env]
            [clojure.java.io :as io]
            [clojure.tools.reader :as reader]
            [cljs.util :as cljs-util]
            [clojure.walk :as walk]
            [re-frame-lint.utils :as utils])
  (:import [java.io File]
           [java.net URL]))

(defn children*
  "Return a vector of vectors of the children node key and the children expression
   of the AST node, if it has any.
   The returned vector returns the childrens in the order as they appear in the
   :children field of the AST, and the children expressions may be either a node
   or a vector of nodes."
  [{:keys [children op] :as ast}]
  (let [children-hotfix (if (and (= op :binding)
                                 (nil? children))
                          [:init]
                          children)]
    (when children-hotfix
      (mapv #(find ast %) children-hotfix))))

(defn children
  "Return a vector of the children expression of the AST node, if it has any.
   The children expressions are kept in order and flattened so that the returning
   vector contains only nodes and not vectors of nodes."
  [ast]
  (persistent!
   (reduce (fn [acc [_ c]] ((if (vector? c) utils/into! conj!) acc c))
           (transient []) (children* ast))))

(defn nodes
  "Returns a lazy-seq of all the nodes in the given AST, in depth-first pre-order."
  [ast]
  (lazy-seq
   (cons ast (mapcat nodes (children ast)))))

(def cljs-warnings
  (-> cljs-ana/*cljs-warnings*
      (assoc :undeclared-var false
             :invalid-protocol-symbol false
             :protocol-invalid-method false)))

(defn- default-and-refer-macros->refer
  ":refer-macros [a] 나 :default a 를 :refer [a] 로 바꾼다."
  [form]
  (if (vector? form)
    (let [ns-symbol (first form)
          options (apply hash-map (rest form))]
      (if (or (:default options)
              (:refer-macros options))
        (let [default-symbol (:default options)
              refer-macros (:refer-macros options)
              refers (cond-> (:refer options
                                     [])
                       (some? default-symbol)
                       (conj default-symbol)

                       (some? refer-macros)
                       (->>
                        (into refer-macros)))
              others (-> options
                         (dissoc :default :refer-macros)
                         (assoc :refer refers))]
          (->> others
               (apply concat)
               (into [ns-symbol])))
        form))
    form))

(defn- strip-include-macros
  ":include-macros true 를 삭제한다."
  [form]
  (if (vector? form)
    (let [ns-symbol (first form)
          options (apply hash-map (rest form))]
      (if (:include-macros options)
        (->> (dissoc options
                     :include-macros)
             (apply concat)
             (into [ns-symbol]))
        form))
    form))

(defn- hotfix-ns* [ns-form]
  (loop [orig ns-form
         others []
         require ()]
    (if (empty? orig)
      (->> require
           (map default-and-refer-macros->refer)
           (map strip-include-macros)
           (list* :require)
           (conj others)
           (seq))
      (let [form (first orig)]
        (cond
          (not (coll? form))
          (recur (rest orig)
                 (conj others form)
                 require)

          (= :require (first form))
          (recur (rest orig)
                 others
                 (concat require
                         (rest form)))

          (= :require-macros (first form))
          (recur (rest orig)
                 others
                 (concat require
                         (rest form)))

          :else
          (recur (rest orig)
                 (conj others form)
                 require))))))

(defn- hotfix-ns
  "shadow-cljs 기능인 :default -> :refer로 수정. macro 관련 기능들 제거.
  근본적으로 rewrite-clj 로 갈아타야함."
  [ns-form]
  (if (= (first ns-form)
         'ns)
    (hotfix-ns* ns-form)
    ns-form))

(defn- hotfix-macro
  "실제 macro 실행을 안하기 떄문에, macro를 파싱이 될 정도로만 적당히 실행시켜준다.
  <any-ns>/go-loop -> loop, <any-ns>/fn -> fn"
  [top-form]
  (walk/prewalk
   (fn [form]
     (cond
       (not (symbol? form))
       form

       (= (name form)
          "go-loop")
       'loop

       (and (qualified-symbol? form)
            (= (name form)
               "fn"))
       'fn

       :else
       form))
   top-form))

(defn analyze-file [f]
  (cljs-env/ensure
   (let [opts nil
         res (cond
               (instance? File f) f
               (instance? URL f) f
               (re-find #"^file://" f) (URL. f)
               :else (io/resource f))
         path    (if (instance? File res)
                   (.getPath ^File res)
                   (.getPath ^URL res))]
     
     (binding [cljs.analyzer/*cljs-ns* 'cljs.user
               cljs.analyzer/*cljs-file* path
               cljs.analyzer/*cljs-warnings* cljs-warnings
               reader/*alias-map* (or reader/*alias-map* {})
               cljs.analyzer/*analyze-deps* false
               cljs.analyzer/*unchecked-if* cljs.analyzer/*unchecked-if*]
       (let [env (assoc (ana-api/empty-env)
                        :build-options opts)]
         #_(cljs.compiler/with-core-cljs)
         (with-open [rdr (io/reader res)]
           (loop [asts []
                  forms (seq (ana-api/forms-seq rdr
                                                (cljs-util/path res)))]
             (if forms
               (let [form (-> (first forms)
                              (hotfix-ns)
                              (hotfix-macro))
                     env (assoc env :ns (cljs-ana/get-namespace cljs-ana/*cljs-ns*))
                     ast (cljs-ana/analyze env
                                           form
                                           nil
                                           opts)]
                 (recur (conj asts
                              ast)
                        (next forms)))
               asts))))))))

(defn trim-env
  "node에서 :env 키 삭제. 디버깅 출력용으로."
  [top-node]
  (walk/prewalk (fn [node]
                  (if (map? node)
                    (dissoc node :env)
                    node))
                top-node))
