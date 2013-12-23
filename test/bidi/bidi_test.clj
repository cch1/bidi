;; Copyright © 2013, JUXT LTD. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(ns bidi.bidi-test
  (:require [clojure.test :refer :all]
            [bidi.bidi :refer :all]
            [ring.mock.request :refer :all]))

(deftest matching-routes-test
  (testing "misc-routes"
    (is (= (match-route ["/blog/foo" 'foo] "/blog/foo")
           {:handler 'foo}))

    (is (= (match-route ["/blog" [["/foo" 'foo]
                                  ["/bar" [["/abc" :bar]]]]]
                        "/blog/bar/abc")
           {:handler :bar}))

    (is (= (match-route ["/blog" [["/foo" 'foo]
                                  [["/bar" :path] :bar]]]
                        "/blog/bar/articles/123/index.html")
           {:handler :bar :params {:path "/articles/123/index.html"}}))

    ;; The example in the README, so make sure it passes!
    (is (= (match-route ["/blog" [["/index.html" 'index]
                                  [["/bar/articles/" :artid "/index.html"] 'article]]]
                        "/blog/bar/articles/123/index.html")
           {:handler 'article :params {:artid "123"}}))

    (is (= (match-route ["/blog" [["/foo" 'foo]
                                  [["/bar/articles/" :artid "/index.html"] 'bar]]]
                        "/blog/bar/articles/123/index.html")
           {:handler 'bar :params {:artid "123"}}))

    (is (= (match-route ["/blog" [[["/articles/" :id "/index.html"] 'foo]
                                  ["/text" 'bar]]]
                        "/blog/articles/123/index.html")
           {:handler 'foo :params {:id "123"}}))))

(deftest unmatching-routes-test
  (let [routes ["/"
                [["blog"
                  [["/index.html" 'blog-index]
                   [["/article/" :id ".html"] 'blog-article-handler]
                   [["/archive/" :id "/" :page ".html"] 'archive-handler]]]
                 [["images/" :path] 'image-handler]]]]

    (testing "unmatching"

      (is
       (= (path-for routes 'blog-index)
          "/blog/index.html"))
      (is
       (= (path-for routes 'blog-article-handler :id 1239)
          "/blog/article/1239.html"))
      (is
       ;; If not all the parameters are specified we expect an error to be thrown
       (thrown? clojure.lang.ExceptionInfo (path-for routes 'archive-handler :id 1239)
                "/blog/archive/1239/section.html"))
      (is
       (= (path-for routes 'archive-handler :id 1239 :page "section")
          "/blog/archive/1239/section.html"))
      (is
       (= (path-for routes 'image-handler :path "")
          "/images/"))

      (is
       (= (path-for routes 'image-handler :path "123.png")
          "/images/123.png")))

    (testing "unmatching with constraints"

      (let [routes ["/" [["blog"
                          [[:get [[["/index"] :index]]]
                           [{:request-method :post :server-name "juxt.pro"}
                            [[["/articles/" :artid] :new-article-handler]]]]]]]]
        (is (= (path-for routes :index)
               "/blog/index"))
        (is (= (path-for routes :new-article-handler :artid 10)
               "/blog/articles/10"))))))

(deftest make-handler-test

  (testing "routes"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [["/index.html" (fn [req] {:status 200 :body "Index"})]
                            [["/article/" :id ".html"] 'blog-article-handler]
                            [["/archive/" :id "/" :page ".html"] 'archive-handler]]]
                          ["images/" 'image-handler]]])]
      (is (= (handler (request :get "/blog/index.html"))
             {:status 200 :body "Index"}))))

  (testing "method constraints"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [[:get [["/index.html" (fn [req] {:status 200 :body "Index"})]]]
                            [:post [["/zip" (fn [req] {:status 201 :body "Created"})]]]]
                           ]]])]

      (is handler)
      (is (= (handler (request :get "/blog/index.html")) {:status 200 :body "Index"}))
      (is (nil? (handler (request :post "/blog/index.html"))))
      (is (= (handler (request :post "/blog/zip")) {:status 201 :body "Created"}))
      (is (nil? (handler (request :get "/blog/zip"))))))

  (testing "other request constraints"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [[:get
                             [["/index"
                               (fn [req] {:status 200 :body "Index"})]
                              [["/article/" :artid "/index.html"]
                               (fn [req] {:status 200 :body (get-in req [:route-params :artid])})]
                              [["/article/" :artid "/another.html"]
                               (fn [req] {:status 200 :body (get-in req [:params :artid])})]]]
                            [{:request-method :post :server-name "juxt.pro"}
                             [["/zip"
                               (fn [req] {:status 201 :body "Created"})]]]]]]])]

      (is handler)
      (is (nil? (handler (request :post "/blog/zip"))))
      (is (= (handler (request :post "http://juxt.pro/blog/zip"))
             {:status 201 :body "Created"}))
      (is (nil? (handler (request :post "/blog/zip"))))
      (testing "artid makes it into :route-params")
      (is (= (handler (request :get "/blog/article/123/index.html"))
             {:status 200 :body "123"}))
      (testing "artid makes it into :params"
        (is (= (handler (request :get "/blog/article/123/another.html"))
               {:status 200 :body "123"})))
      (testing "artid makes it into :params, but non-destructively"
        (is (= (handler (-> (request :get "/blog/article/123/another.html"
                                        {:artid "foo"})
                            (assoc :params {:artid "foo"})))
               {:status 200 :body "foo"}))))))
