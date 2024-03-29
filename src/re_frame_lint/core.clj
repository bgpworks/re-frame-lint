(ns re-frame-lint.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :as cli]
            [re-frame-lint.ast :as ast]
            [re-frame-lint.utils :as utils]
            [re-frame-lint.lints :as lints])
  (:import java.io.File))

(defn ends-with?
  "Returns true if the java.io.File ends in any of the strings in coll"
  [^java.io.File file coll]
  (some #(.endsWith (.getName file) %) coll))

(defn clojure-file?
  "Returns true if the java.io.File represents a Clojure source file.
  Extensions taken from https://github.com/github/linguist/blob/master/lib/linguist/languages.yml"
  [^java.io.File file]
  (and (.isFile file)
       (ends-with? file [".clj" ".cl2" ".cljc" ".cljs" ".cljscm" ".cljx" ".hic" ".hl"])))

(defn find-clojure-sources-in-dir
  "Searches recursively under dir for Clojure source files.
  Returns a sequence of File objects, in breadth-first sort order.
  Taken from clojure.tools.namespace.find"
  [^File dir]
  ;; Use sort by absolute path to get breadth-first search.
  (sort-by #(.getAbsolutePath ^java.io.File %)
           (filter clojure-file? (file-seq dir))))


(defn- refer-info
  "사용되는 곳. 인자들을 `args`로 받는다."
  [filepath line-info key args]
  (assoc line-info
         :filepath filepath
         :key key
         :args args))

(defn- guess-arity
  "re-frame event/subscribe handler의 두번째 인자를 분석하여 arity를 추론한다."
  [form]
  (cond
    ;; 아예 선언조차 안하고 생략한 경우.
    (nil? form)
    1

    (= form '_)
    1

    (vector? form)
    (if (= (second (reverse form))
           :as)
      (- (count form) 2)
      (count form))

    :else
    nil))

(defn- decl-sub-info
  "선언. 인자 선언을 `spec`으로 받는다."
  [filepath line-info key cb-spec input-signals]
  (assoc line-info
         :filepath filepath
         :key key
         :spec cb-spec
         :arity (guess-arity (second cb-spec))
         :input-signal-count (count input-signals)))

(defn- decl-event-info
  "선언. 인자 선언을 `spec`으로 받는다."
  [filepath line-info key cb-spec]
  (assoc line-info
         :filepath filepath
         :key key
         :spec cb-spec
         :arity (guess-arity (second cb-spec))))

(defn- decl-fx-info
  [filepath line-info key cb-spec]
  (assoc line-info
         :filepath filepath
         :key key
         :spec cb-spec))

(defn- collect-dependant-subs-calls
  ":<- syntactic sugar만 지원. signals function은 지원 안함."
  [reg-sub-node]
  (let [mid-args (-> (:args reg-sub-node)
                     butlast
                     next)]
    (loop [found-refers []
           [maybe-> vector-node :as args] mid-args]
      (cond
        (nil? maybe->)
        found-refers

        (not= (:val maybe->)
              :<-)
        (recur found-refers
               (next args))
        
        (= (:op vector-node)
           :vector)
        (if (get-in vector-node
                    [:items 0 :val])
          (recur (conj found-refers
                       (:form vector-node))
                 (nnext args))
          (do
            (log/error "Invalid reg-sub syntax:" (:form reg-sub-node))
            (recur found-refers
                   (nnext args))))

        :else
        (do
          (log/error "Invalid reg-sub syntax:" (:form reg-sub-node))
          (recur found-refers
                 (next args)))))))

(defmulti ^:private get-line-info :op)

(defmethod get-line-info :invoke [invoke-node]
  #_"invoke node의 line 정보. 왜인지 모르겠지만 자기 자신의 위치는 없다.
  대신 함수의 위치는 있는데, 대충 비슷할 테니 이걸로 쓴다."
  (let [env (get-in invoke-node
                    [:fn :env])]
    {:line (:line env)
     :column (:column env)
     :ns (get-in env [:ns :name])}))

(defmethod get-line-info :def [def-node]
  (let [meta (get-in def-node
                    [:var :info :meta])]
    {:line (:line meta)
     :column (:column meta)
     :ns (:ns def-node)}))

(defn- collect-vectors-with-leading-qualified-keyword [top-node]
  (->> (ast/nodes top-node)
       (keep (fn [node]
               (when (and (= (:op node)
                             :vector)
                          (-> node
                              :form
                              first
                              qualified-keyword?))
                 (-> node :form))))))

(defn- collect-dependant-event-calls
  "연쇄적으로 부르는 deps 가져오기 (dispatch, dispatch-later, dispatch-n, api, api-n, ...)
  1. child node 중 vector를 뒤져서 해당 컬럼을 빼온다.
  2. 무식하지만 vector의 첫번째 child로 qualified keyword가 나오면 죄다 event-key라 간주한다.
  구현이 간단한 2를 씀."
  [handler-node]
  (assert (= (:op handler-node)
             :fn)
          "should be called with fx node")
  (->> handler-node
       (collect-vectors-with-leading-qualified-keyword)
       (filter (fn [call-vector]
                 (not (utils/fx-keyword? (first call-vector)))))))

(defn- collect-dependant-fx-calls
  "연쇄적으로 부르는 fx deps 가져오기 (:fx, event key 참조, ...)
  무식하지만 fx-keyword? 인 애를 다 모은다.
  인자는 당장 쓸껀 아니므로 모으지 않음."
  [handler-node]
  (assert (= (:op handler-node)
             :fn)
          "should be called with fx node")
  (->> handler-node
       (ast/nodes)
       (keep (fn [node]
               (when (and (= (:op node)
                             :const)
                          (utils/fx-keyword? (:val node)))
                 ;; 인자는 당장 쓸껀 아니므로 모으지 않음.
                 [(:val node)])))))

(defn- get-handler-node [reg-event-fx-node]
  (let [handler-node (last (:args reg-event-fx-node))]
    (if (= (:op handler-node)
           :fn)
      handler-node
      (let [line-info (get-line-info reg-event-fx-node)]
        (log/error "Last argument of reg-event-fx is not a function:"
                   line-info)
        nil))))

(defn- get-arg-form-from-method-form
  [filepath line-info method-form]
  (assert (seq? method-form)
          (str "method-form is not list: "
               (with-out-str
                 (pprint/pprint method-form))
               " of type "
               (type method-form)
               "\n"
               filepath
               line-info))
  (let [args-form (first method-form)
        body-form (second method-form)
        has-destructing? (and (seq? body-form)
                              (= (first body-form)
                                 'clojure.core/let))]
    (if has-destructing?
      (let [dest-map (->> method-form
                         second
                         second
                         reverse
                         (apply hash-map))]
        (mapv dest-map
              args-form))
      args-form)))

(defn- get-subs-cb-fn
  "reg-sub의 computation function에 (memoize (fn [_ _] ))를 허용하기 위한 hack."
  [cb-node filepath line-info]
  (case (:op cb-node)
    :fn
    cb-node

    :invoke
    (let [last-arg (-> cb-node :args last)]
      (assert (= (:op last-arg) :fn)
              "reg-sub handler not found. unhandled cased.")
      last-arg)

    ;; else
    (assert false
            (str "unknown form of subscription handler "
                 filepath
                 line-info))))

(defn- get-cb-arg-form
  "마지막 인자가 cb 함수라 가정하고, cb 함수의 arg binding form을 가져온다.
  간단하게는 (-> node :form last second) 라고 보면 된다."
  [filepath line-info decl-node]
  (let [cb-node (-> decl-node
                    :args
                    last
                    (get-subs-cb-fn filepath
                                    line-info))]
    (assert (= (:op cb-node) :fn))
    (assert (= (count (:methods cb-node)) 1)
            (str "multi-arity callback funtion? "
                 filepath
                 line-info))
    (get-arg-form-from-method-form filepath
                                   line-info
                                   (get-in cb-node
                                           [:methods 0 :form]))))

(defn- ^String normalize-path
  "Normalizes a file path on Unix systems by eliminating '.' and '..' from it.
   No attempts are made to resolve symbolic links."
  [^String file-path]
  (loop [dest []
         src (string/split file-path #"/")]
    (if (empty? src)
      (string/join "/" dest)
      (let [curr (first src)]
        (cond (= curr ".") (recur dest (rest src))
              (= curr "..") (recur (vec (butlast dest)) (rest src))
              :else (recur (conj dest curr) (rest src)))))))

(defn- reg-event-fx-handler?
  "(defn- ^:reg-event-fx ...) 로 정의된 함수는 reg-event-fx 에서 불린다 가정하고 검사.
  reg-event-fx handler body에서 invoke 노드 참조해서 다 따라갈 수도 있지만,
  구현이 복잡하니 명시적으로 지정하는걸로."
  [node]
  (and (= (:op node)
          :def)
       (= (get-in node
                  [:init :op])
          :fn)
       (get-in node
               [:var :info :meta :reg-event-fx])))

(defn- collect-refer-info-from-handler [aux filepath line-info handler-node]
  (let [deps-event-calls (collect-dependant-event-calls handler-node)
        deps-fx-calls (collect-dependant-fx-calls handler-node)]
    (cond-> aux
      (seq deps-event-calls)
      (update :refer-event-key
              utils/into!
              (map (fn [event-call]
                     (refer-info filepath
                                 line-info
                                 (first event-call)
                                 event-call))
                   deps-event-calls))

      (seq deps-fx-calls)
      (update :refer-fx-key
              utils/into!
              (map (fn [fx-call]
                     (refer-info filepath
                                 line-info
                                 (first fx-call)
                                 fx-call))
                   deps-fx-calls)))))

(defn- collect-call-info-from-file [aux ^java.io.File file]
  (let [filepath (-> (.getAbsolutePath file)
                     (normalize-path))
        file-ast (ast/analyze-file file)
        nodes (mapcat ast/nodes file-ast)]
    (reduce (fn [aux node]
              (cond
                ;; ^:reg-event-fx 가 붙은 함수들
                (reg-event-fx-handler? node)
                (let [line-info (get-line-info node)]
                  (collect-refer-info-from-handler aux
                                                   filepath
                                                   line-info
                                                   (:init node)))

                ;; 외에는 invoke node만 검사
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
                          (refer-info filepath
                                      line-info
                                      sub-key
                                      (get-in node
                                              [:args 0 :form]))))

                ;; reg-fx
                (= (get-in node [:fn :name])
                   're-frame.core/reg-fx)
                (let [line-info (get-line-info node)
                      decl-fx-key (get-in node
                                          [:args 0 :val])
                      decl-cb-arg-form (get-cb-arg-form filepath
                                                        line-info
                                                        node)]
                  (update aux
                          :decl-fx-key
                          conj!
                          (decl-fx-info filepath
                                        line-info
                                        decl-fx-key
                                        decl-cb-arg-form)))

                ;; reg-sub
                (= (get-in node [:fn :name])
                   're-frame.core/reg-sub)
                (let [line-info (get-line-info node)
                      decl-sub-key (get-in node
                                           [:args 0 :val])
                      decl-cb-arg-form (get-cb-arg-form filepath
                                                        line-info
                                                        node)
                      deps-subs-calls (collect-dependant-subs-calls node)]
                  (cond-> aux
                    decl-sub-key
                    (update :decl-sub-key
                            conj!
                            (decl-sub-info filepath
                                           line-info
                                           decl-sub-key
                                           decl-cb-arg-form
                                           deps-subs-calls))

                    (seq deps-subs-calls)
                    (update :refer-sub-key
                            utils/into!
                            (map (fn [subs-call]
                                   (refer-info filepath
                                               line-info
                                               (first subs-call)
                                               subs-call))
                                 deps-subs-calls))))

                ;; reg-event-fx
                (= (get-in node [:fn :name])
                   're-frame.core/reg-event-fx)
                (let [line-info (get-line-info node)
                      decl-event-key (get-in node
                                             [:args 0 :val])
                      decl-cb-arg-form (get-cb-arg-form filepath
                                                        line-info
                                                        node)
                      handler-node (get-handler-node node)]
                  (cond-> aux
                    decl-event-key
                    (update :decl-event-key
                            conj!
                            (decl-event-info filepath
                                             line-info
                                             decl-event-key
                                             decl-cb-arg-form))

                    handler-node
                    (collect-refer-info-from-handler filepath
                                                     line-info
                                                     handler-node)))

                ;; reg-event-db
                (= (get-in node [:fn :name])
                   're-frame.core/reg-event-db)
                (let [line-info (get-line-info node)
                      decl-event-key (get-in node
                                             [:args 0 :val])
                      decl-cb-arg-form (get-cb-arg-form filepath
                                                        line-info
                                                        node)]
                  (update aux
                          :decl-event-key
                          conj!
                          (decl-event-info filepath
                                           line-info
                                           decl-event-key
                                           decl-cb-arg-form)))

                ;; dispatch / dispatch-sync
                (contains? #{'re-frame.core/dispatch
                             're-frame.core/dispatch-sync}
                           (get-in node [:fn :name]))
                (let [event-key (get-in node
                                        [:args 0 :items 0 :val])
                      event-call-form (get-in node
                                              [:args 0 :form])
                      line-info (get-line-info node)]
                  (if event-key
                    (update aux
                            :refer-event-key
                            conj!
                            (refer-info filepath
                                        line-info
                                        event-key
                                        event-call-form))
                    ;; 간접 호출: (re-frame/dispatch (conj on-finally xhr res))
                    aux))
                
                :else
                aux))
            aux
            nodes)))

(defn- collect-call-info [paths]
  (let [source-files (mapcat #(-> % io/file find-clojure-sources-in-dir)
                             paths)]
    (loop [[file & remain-files] source-files
           aux {:decl-sub-key (transient [])
                :refer-sub-key (transient [])
                :decl-event-key (transient [])
                :refer-event-key (transient [])
                :decl-fx-key (transient [])
                :refer-fx-key (transient [])}]
      (if (nil? file)
        (reduce-kv (fn [aux k v]
                     (assoc aux k (persistent! v)))
                   {}
                   aux)
        (recur remain-files
               (collect-call-info-from-file aux
                                            file))))))


(defn- lint [paths opts]
  (let [call-info (collect-call-info paths)]
    {:some-warnings (->> (concat [(lints/lint-unknown-sub-keys call-info)
                                  (lints/lint-unknown-event-keys call-info)
                                  (lints/lint-unused-sub-keys call-info)
                                  (lints/lint-unused-event-keys call-info)
                                  (lints/lint-signal-args-mismatch call-info)
                                  (lints/lint-subs-arity-mismatch call-info)
                                  (lints/lint-event-arity-mismatch call-info)
                                  (lints/lint-misused-private-sub-keys call-info)
                                  (lints/lint-misused-private-event-keys call-info)
                                  (lints/lint-should-private-sub-keys call-info)
                                  (lints/lint-should-private-event-keys call-info)]
                                 (when (:lint-fx opts)
                                   [(lints/lint-invalid-fx-keys call-info)
                                    (lints/lint-unknown-fx-keys call-info)
                                    (lints/lint-unused-fx-keys call-info)
                                    (lints/lint-should-private-fx-keys call-info)]))
                         (some true?))}))

(defn- lint-from-cmdline [paths opts]
  (let [ret (lint paths
                  opts)]
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

(defn print-sample-ast
  "lein run -m re-frame-lint.core/print-sample-ast > resources/analyze_me_ast.edn"
  []
  (-> (io/resource "analyze_me.cljs")
      (ast/analyze-file)
      (ast/trim-env)
      (pprint/pprint)))

(def ^:private cli-options
  [[nil "--lint-fx" "Enable linters for reg-fx"]
   ["-h" "--help"]])

(defn- usage [options-summary]
  (->> ["Simple linter for re-frame."
        ""
        "Usage: re-frame-lint [options] path1 path2 ..."
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn- validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options
                arguments
                errors
                summary]} (cli/parse-opts args
                                          cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary)
       :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      (empty? arguments)
      {:exit-message "Paths are not given."}

      :else
      {:paths arguments
       :options options})))

(defn- exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [paths
                options
                exit-message
                ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1)
            exit-message)
      (lint-from-cmdline paths
                         options))))
