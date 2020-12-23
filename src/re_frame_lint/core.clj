(ns re-frame-lint.core
  (:gen-class)
  (:require [clojure.set :as clj-set]
            [clojure.java.io :as io]
            [re-frame-lint.ast :as ast]
            [re-frame-lint.utils :as utils])
  (:import java.io.File))

(defn ends-with?
  "Returns true if the java.io.File ends in any of the strings in coll"
  [file coll]
  (some #(.endsWith (.getName file) %) coll))

(defn clojure-file?
  "Returns true if the java.io.File represents a Clojure source file.
  Extensions taken from https://github.com/github/linguist/blob/master/lib/linguist/languages.yml"
  [file]
  (and (.isFile file)
       (ends-with? file [".clj" ".cl2" ".cljc" ".cljs" ".cljscm" ".cljx" ".hic" ".hl"])))

(defn find-clojure-sources-in-dir
  "Searches recursively under dir for Clojure source files.
  Returns a sequence of File objects, in breadth-first sort order.
  Taken from clojure.tools.namespace.find"
  [^File dir]
  ;; Use sort by absolute path to get breadth-first search.
  (sort-by #(.getAbsolutePath ^File %)
           (filter clojure-file? (file-seq dir))))


(defn- get-deps-sub-keys [reg-sub-node]
  (let [mid-args (-> (:args reg-sub-node)
                 butlast
                 next)]
    (loop [found-keys []
           [maybe-> vector-node :as args] mid-args]
      (cond
        (nil? maybe->)
        found-keys

        (not= (:val maybe->)
              :<-)
        (recur found-keys
               (next args))
        
        (= (:op vector-node)
           :vector)
        (if-let [sub-key (get-in vector-node
                                 [:items 0 :val])]
          (recur (conj found-keys
                       sub-key)
                 (nnext args))
          (recur found-keys
                 (nnext args)))

        :else
        (do
          (prn "Invalid reg-sub syntax:" (:form reg-sub-node))
          (recur found-keys
                 (next args)))))))

(defn- collect-leading-qualified-keyword-of-vector [top-node]
  (->> (ast/nodes top-node)
       ;; vectors
       (filter (fn [node]
                 (= (:op node)
                    :vector)))
       ;; first child's value
       (keep (comp :val first :items))
       ;; qualified keywords
       (filter qualified-keyword?)))

(defn- get-deps-event-keys
  "연쇄적으로 부르는 deps 가져오기 (dispatch, dispatch-later, dispatch-n, api, api-n, ...)
  1. child node 중 vector를 뒤져서 해당 컬럼을 빼온다.
  2. 무식하지만 vector의 첫번째 child로 qualified keyword가 나오면 죄다 event-key라 간주한다.
  구현이 간단한 2를 씀."
  [reg-event-fx-node]
  (let [handler-node (last (:args reg-event-fx-node))]
    (when (= (:op handler-node)
             :fn)
      (collect-leading-qualified-keyword-of-vector handler-node))))

(defn- get-line-info
  "invoke node의 line 정보. 왜인지 모르겠지만 자기 자신의 위치는 없다.
  대신 함수의 위치는 있는데, 대충 비슷할 테니 이걸로 쓴다."
  [invoke-node]
  (-> (get-in invoke-node
              [:fn :env])
      (select-keys [:line :column])))

(defn- key-with-line-info
  "meta로 바꿀 수도 있으니, 일단 함수로 빼본다."
  [filepath line-info key]
  (assoc line-info
         :key key
         :filepath filepath))

(defn- collect-call-info-from-file [aux file]
  (let [filepath (.getAbsolutePath file)
        file-ast (ast/analyze-file file)
        nodes (mapcat ast/nodes file-ast)]
    (reduce (fn [aux node]
              (cond
                (not= (:op node)
                      :invoke)
                aux

                ;; subscribe
                (= (get-in node [:fn :name])
                   're-frame.core/subscribe)
                (let [sub-key (get-in node
                                      [:args 0 :items 0 :val])
                      ;; :args의 env에는 line / column이 채워져있지 않다. 이걸 대신 씀.
                      line-info (get-line-info node)]
                  (update aux
                          :refer-sub-key
                          conj!
                          (key-with-line-info filepath
                                              line-info
                                              sub-key)))

                ;; reg-sub
                (= (get-in node [:fn :name])
                   're-frame.core/reg-sub)
                (let [decl-sub-key (get-in node
                                           [:args 0 :val])
                      deps-sub-keys (get-deps-sub-keys node)
                      
                      line-info (get-line-info node)]
                  (cond-> aux
                    decl-sub-key
                    (update :decl-sub-key
                            conj!
                            (key-with-line-info filepath
                                                line-info
                                                decl-sub-key))

                    (seq deps-sub-keys)
                    (update :refer-sub-key
                            utils/into!
                            (map (partial key-with-line-info
                                          filepath
                                          line-info)
                                 deps-sub-keys))))

                ;; reg-event-fx
                (= (get-in node [:fn :name])
                   're-frame.core/reg-event-fx)
                (let [decl-event-key (get-in node
                                             [:args 0 :val])
                      deps-event-keys (get-deps-event-keys node)
                      line-info (get-line-info node)]
                  (cond-> aux
                    decl-event-key
                    (update :decl-event-key
                            conj!
                            (key-with-line-info filepath
                                                line-info
                                                decl-event-key))
                    (seq deps-event-keys)
                    (update :refer-event-key
                            utils/into!
                            (map (partial key-with-line-info
                                          filepath
                                          line-info)
                                 deps-event-keys))))

                ;; reg-event-db
                (= (get-in node [:fn :name])
                   're-frame.core/reg-event-db)
                (let [decl-event-key (get-in node
                                             [:args 0 :val])
                      line-info (get-line-info node)]
                  (update aux
                          :decl-event-key
                          conj!
                          (key-with-line-info filepath
                                              line-info
                                              decl-event-key)))

                ;; dispatch / dispatch-sync
                (contains? #{'re-frame.core/dispatch
                             're-frame.core/dispatch-sync}
                           (get-in node [:fn :name]))
                (let [event-key (get-in node
                                        [:args 0 :items 0 :val])
                      line-info (get-line-info node)]
                  (if event-key
                    (update aux
                            :refer-event-key
                            conj!
                            (key-with-line-info filepath
                                                line-info
                                                event-key))
                    ;; 간접 호출: (re-frame/dispatch (conj on-finally xhr res))
                    aux))
                
                :else
                aux))
            aux
            nodes)))

(defn- collect-call-info [opts]
  (let [source-files (mapcat #(-> % io/file find-clojure-sources-in-dir)
                             (:source-paths opts))]
    (loop [[file & remain-files] source-files
           aux {:decl-sub-key (transient [])
                :refer-sub-key (transient [])
                :refer-event-key (transient [])
                :decl-event-key (transient [])}]
      (if (nil? file)
        (reduce-kv (fn [aux k v]
                     (assoc aux k (persistent! v)))
                   {}
                   aux)
        (recur remain-files
               (collect-call-info-from-file aux
                                            file))))))

(defn- find-unknown-sub-key [call-info]
  (let [registered-sub-keys (->> (:decl-sub-key call-info)
                                 (map :key)
                                 (set))]
    (->> (:refer-sub-key call-info)
         (filter (comp not registered-sub-keys :key)))))

(defn- print-info [info]
  (println (:filepath info) "line:" (:line info) "column:" (:column info) (:key info)))

(defn- print-infos [infos]
  (doseq [info infos]
    (print-info info))
  (println ""))

(defn- lint-unknown-sub-keys [call-info]
  (let [unknown-keys (find-unknown-sub-key call-info)]
    (when (seq unknown-keys)
      (prn "Unknown sub keys:")
      (print-infos unknown-keys)
      true)))

(defn- find-unknown-event-key [call-info]
  (let [registered-event-key (->> (:decl-event-key call-info)
                                  (map :key)
                                  (set))]
    (->> (:refer-event-key call-info)
         (filter (comp not registered-event-key :key)))))

(defn- lint-unknown-event-keys [call-info]
  (let [unknown-keys (find-unknown-event-key call-info)]
      (when (seq unknown-keys)
        (prn "Unknown event keys:")
        (print-infos unknown-keys)
        true)))

(defn- find-unused-sub-key [call-info]
  (let [used-keys (->> (:refer-sub-key call-info)
                          (map :key)
                          (set))]
    (->> (:decl-sub-key call-info)
         (filter (comp not used-keys :key)))))

(defn- lint-unused-sub-keys [call-info]
  (let [unused-keys (find-unused-sub-key call-info)]
    (when (seq unused-keys)
      (prn "Unusued sub keys:")
      (print-infos unused-keys)
      true)))


(defn- find-unused-event-key [call-info]
  (let [used-keys (->> (:refer-event-key call-info)
                       (map :key)
                       (set))]
    (->> (:decl-event-key call-info)
         (filter (comp not used-keys :key)))))

(defn- lint-unused-event-keys [call-info]
  (let [unused-keys (find-unused-event-key call-info)]
    (when (seq unused-keys)
      (prn "Unused event keys:")
      (print-infos unused-keys)
      true)))

(defn- lint [opts]
  (let [call-info (collect-call-info opts)
        has-unknown-sub-key? (lint-unknown-sub-keys call-info)
        has-unknown-event-key? (lint-unknown-event-keys call-info)
        has-unused-sub-key? (lint-unused-sub-keys call-info)
        has-unused-event-key? (lint-unused-event-keys call-info)]
    {:some-warnings (or has-unknown-sub-key?
                        has-unknown-event-key?
                        has-unused-sub-key?
                        has-unused-event-key?)}))

(defn- lint-from-cmdline [opts]
  (let [ret (lint opts)]
    (if (:some-warnings ret)
      ;; Exit with non-0 exit status for the benefit of any shell
      ;; scripts invoking Eastwood that want to know if there were no
      ;; errors, warnings, or exceptions.
      (System/exit 1)
      ;; Does not use future, pmap, or clojure.shell/sh now
      ;; (at least not yet), but it may evaluate code that does when
      ;; linting a project.  Call shutdown-agents to avoid the
      ;; 1-minute 'hang' that would otherwise occur.
      (shutdown-agents))))

(defn -main [& paths]
  (lint-from-cmdline {:source-paths paths}))
