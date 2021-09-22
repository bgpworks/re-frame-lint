# re-frame-lint

[clojurescript analyzer api](https://cljs.github.io/api/compiler/cljs.analyzer.api/)를 이용하여 ast tree를 생성하여 단순 분석. 

- classlib 같은 dependency를 잡고 싶지 않아서 개별 파일로 분석하도록 함.
- 3rd party macro는 무시함.

## Installation

clone this project somewhere.

## Usage

    $ lein run ../PATH/TO/boxhero-web/src/cljs/boxhero

## TODO

- [x] argument spec check
- lint 설정 option으로 설정할 수 있도록.
- logger library 붙이기.
- 쉽게 실행할 수 있도록 lein plugin 으로 동작하도록 한다.
- 속도 향상
  - 파일 단위로 캐쉬 저장. [cljs.analyzer](https://github.com/clojure/clojurescript/blob/946348da8eb705da23f465be29246d4f8b73d45f/src/main/clojure/cljs/analyzer.cljc#L4636)
  - grallvm 이용한 native compile. clojurescript를 deps로 가지고 있어서 아마 안될 듯.
- 에디터 호환을 위한 output 포멧. (GNU grep 포멧, ...)

## Limitation

- `reg-event-fx` body에서 참조하는 event key를 정확히 찾지는 못한다. (handler function body를 뒤져서 vector의 첫번째 인자로 qualified-keyword 가 등장하면 무조건 event-key라고 가정한다.)
- 3rd party macro는 무시하기 때문에 올바르게 분석이 안될 수도 있음.
- re-frame library를 직접 호출하는 코드들만 모아서 검색하기 때문에 wrapper 함수를 만들어서 간접 호출을 하면 찾을 수 없다.
- 에러 위치가 정확하지는 않다. cljs.analyzer가 parameter의 파일 위치를 기록해주지 않는다. 대신 문제가 있는 re-frame library call 위치를 보고해준다.


```cljs
(defn subscribe-indirect [subs-key]
  (re-frame.core/subscribe [subs-key]))
  
  
(defn hello []
  [:div
    ;; :subs-key 는 인식하지 못함.
    [:span @(subscribe-indirect :subs-key)]
    ;; :visible-key 는 잘 인식.
    [:span @(re-frame.core/subscribe [:visible-key])]])
```

### reg-event-fx 함수 쪼개기

`reg-event-fx` 함수가 너무 뚱뚱해서 body 일부를 함수로 빼고 싶을 때는, 해당 함수 metadata에 `:reg-event-fx` 를 추가한다.

```cljs
(rf/reg-event-fx
 :to-phase/signing-in-by-provider
 (fn [_ [_ provider-key user-info]]
   {:fx [[:dispatch [::_set-phase :signing-in-by-provider]]
         [:api-call {:url (str "/api/user/sign-in/"
                               (name provider-key))
                     :method "POST"
                     :body (-> {:token (:token user-info)}
                               (util/map->qs))
                     :on-success login-util/boxhero-login
                     :on-error (fn [xhr res]
                                 (let [code (:code res)]
                                   (condp = code
                                     :user-not-found
                                     (rf/dispatch [:to-phase/sign-up-by-provider
                                                   provider-key
                                                   user-info])

                                     ;;else
                                     (do
                                       (error-handler xhr
                                                      res)
                                       (rf/dispatch [:to-phase/entry])))))}]]}))
```

에서 api-call 부분을 함수를 빼고 싶으면 아래와 같이 뺀다.

```cljs
(defn- ^:reg-event-fx sign-in-api [provider-key user-info]
  {:url (str "/api/user/sign-in/"
             (name provider-key))
   :method "POST"
   :body (-> {:token (:token user-info)}
             (util/map->qs))
   ;; TODO: 개선
   :on-success login-util/boxhero-login
   :on-error (fn [xhr res]
               (let [code (:code res)]
                 (condp = code
                   :user-not-found
                   (rf/dispatch [:to-phase/sign-up-by-provider
                                 provider-key
                                 user-info])

                   ;;else
                   (do
                     (error-handler xhr
                                    res)
                     (rf/dispatch [:to-phase/entry])))))})

(rf/reg-event-fx
 :to-phase/signing-in-by-provider
 (fn [_ [_ provider-key user-info]]
   {:fx [[:dispatch [::_set-phase :signing-in-by-provider]]
         [:api-call (sign-in-api provider-key
                                 user-info)]]}))
```


## Memo

### tools.analyzer 관련

[tools.analyzer](https://github.com/clojure/tools.analyzer)를 바로 쓸 수 없다.
`:binding` 노드가 완벽호환되지 않는다. [CLJS-1461](https://clojure.atlassian.net/browse/CLJS-1461).
`:children`이 빠졌는데, [ast-ref.edn](https://github.com/clojure/clojurescript/blob/master/ast-ref/ast-ref.edn) 을 보면 있어야는데 빠진 것 같다.


### rewrite-clj 관련

clojurescript를 직접 써서 좀 애매한 부분들이 있다. [ast.clj](src/re_frame_lint/ast.clj) 에서 각종 hotfix를 하고 있다.

필요한 정도를 빠르게 분석하기 위해서는 다른 툴들 처럼 [rewrite-clj](https://github.com/xsc/rewrite-clj) 기반으로 바꾸는게 낫겠다.

- [trin](https://github.com/benedekfazekas/trin/tree/master/trin)
- [kibit](https://github.com/jonase/kibit)
- [clj-kondo](https://github.com/borkdude/clj-kondo)
- [clojure-lsp](https://github.com/snoe/clojure-lsp)


### Graalvm 빌드

https://www.innoq.com/en/blog/native-clojure-and-graalvm/
https://github.com/kkinnear/zprint/

참고해서 하려고 했는데 잘 안됨.

```
Original exception that caused the problem: org.graalvm.compiler.core.common.PermanentBailoutException: Frame states being merged are incompatible: unbalanced monitors - locked objects do not match
```

locking 문제는 clojure 버전 올리면 된다고 하는데, clojurescript 자체가 graalvm 빌드가 안되는거 아닐까.
zprint도 보면 clojurescript deps에서 제거하고 있다.

rewrite-clj로 갈아타야 grallvm 빌드가 가능할 듯.

## License

Copyright © 2020 BGPWORKS

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
