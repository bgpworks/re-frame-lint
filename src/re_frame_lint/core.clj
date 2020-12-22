(ns re-frame-lint.core
  (:gen-class)
  (:require [clojure.set :as clj-set]
            [clojure.java.io :as io]
            [re-frame-lint.ast :as ast])
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

(defn- collect-call-info [opts]
  (let [source-files (mapcat #(-> % io/file find-clojure-sources-in-dir)
                             (:source-paths opts))
        calls_ (atom {})]
    (doseq [file source-files]
      (let [filepath (.getAbsolutePath file)
            file-ast (ast/analyze-file file)
            calls (reduce (fn [aux node]
                            (cond
                              (not= (:op node)
                                    :invoke)
                              aux

                              ;; subscribe
                              (= (get-in node [:fn :name])
                                 're-frame.core/subscribe)
                              (let [sub-key (get-in node
                                                    [:args 0 :form 0])]
                                (update aux :refer-sub-key conj sub-key))

                              ;; reg-sub
                              (= (get-in node [:fn :name])
                                 're-frame.core/reg-sub)
                              (let [decl-sub-key (get-in node
                                                         [:args 0 :val])
                                    deps-sub-keys (get-deps-sub-keys node)]
                                (cond-> aux
                                  decl-sub-key 
                                  (update :decl-sub-key conj decl-sub-key)

                                  (seq deps-sub-keys)
                                  (update :refer-sub-key into deps-sub-keys)))

                              ;; reg-event-fx
                              ;; TODO: 연쇄적으로 부르는 deps 가져오기 (dispatch-n, dispatch-later, dispatch-n)
                              (= (get-in node [:fn :name])
                                 're-frame.core/reg-event-fx)
                              (let [decl-event-key (get-in node
                                                           [:args 0 :val])]
                                (update aux :decl-event-key conj decl-event-key))

                              ;; reg-event-db
                              (= (get-in node [:fn :name])
                                 're-frame.core/reg-event-db)
                              (let [decl-event-key (get-in node
                                                           [:args 0 :val])]
                                (update aux :decl-event-key conj decl-event-key))

                              ;; dispatch
                              (= (get-in node [:fn :name])
                                 're-frame.core/dispatch)
                              (let [event-key (get-in node
                                                      [:args 0 :form 0])]
                                (if event-key
                                  (update aux :refer-event-key conj event-key)
                                  ;; 간접 호출: (re-frame/dispatch (conj on-finally xhr res))
                                  aux))

                              ;; dispatch-sync
                              (= (get-in node [:fn :name])
                                 're-frame.core/dispatch-sync)
                              (let [event-key (get-in node
                                                      [:args 0 :form 0])]
                                (if event-key
                                  (update aux :refer-event-key conj event-key)
                                  ;; 간접 호출: (re-frame/dispatch (conj on-finally xhr res))
                                  aux))
                              
                              :else
                              aux))
                          {:decl-sub-key []
                           :refer-sub-key []
                           :refer-event-key []
                           :decl-event-key []}
                          (mapcat ast/nodes file-ast))]
        (swap! calls_
               assoc
               filepath
               calls)))
    @calls_))

(defn- find-unknown-sub-key [call-info-per-file]
  (let [registered-sub-key (->> (vals call-info-per-file)
                                (mapcat :decl-sub-key)
                                (set))
        used-sub-key (->> (vals call-info-per-file)
                          (mapcat :refer-sub-key)
                          (set))]
    (clj-set/difference used-sub-key
                        registered-sub-key)))

(defn- find-unknown-event-key [call-info-per-file]
  (let [registered-event-key (->> (vals call-info-per-file)
                                  (mapcat :decl-event-key)
                                  (set))
        used-event-key (->> (vals call-info-per-file)
                            (mapcat :refer-event-key)
                            (set))]
    (clj-set/difference used-event-key
                        registered-event-key)))

(defn- lint-unknown-sub-keys [call-info-per-file]
  (let [unknown-keys (find-unknown-sub-key call-info-per-file)]
    (when (seq unknown-keys)
      (prn "Unknown sub keys:" unknown-keys)
      true)))

(defn- lint-unknown-event-keys [call-info-per-file]
  (let [unknown-keys (find-unknown-event-key call-info-per-file)]
      (when (seq unknown-keys)
        (prn "Unknown event keys:" unknown-keys)
        true)))

(defn- lint [opts]
  (let [call-info-per-file (collect-call-info opts)
        has-unknown-sub-key? (lint-unknown-sub-keys call-info-per-file)
        has-unknown-event-key? (lint-unknown-event-keys call-info-per-file)]
    {:some-warnings (or has-unknown-sub-key?
                        has-unknown-event-key?)}))

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
