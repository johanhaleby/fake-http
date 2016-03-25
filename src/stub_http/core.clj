(ns stub-http.core
  (:import (fi.iki.elonen NanoHTTPD NanoHTTPD$IHTTPSession NanoHTTPD$Response$Status NanoHTTPD$Response$IStatus)
           (clojure.lang IFn IPersistentMap PersistentArrayMap)
           (java.net ServerSocket)
           (java.util HashMap)
           (java.io Closeable))
  (:require [clojure.string :refer [split blank? lower-case]]
            [clojure.test :refer [function?]]))

(defn map-kv [fk fv m]
  "Maps the keys and values in a map with the supplied functions.
  fk = the function for the keys
  fv = the function for the values"
  (into {} (for [[k v] m] [(fk k) (fv v)])))

(defn- keywordize-keys
  "Recursively transforms all map keys from strings to keywords."
  [m]
  (let [f (fn [[k v]] (if (string? k) [(keyword k) v] [k v]))]
    (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn- substring-before [str before]
  "Return the substring of string <str> before string <before>. If no match then <str> is returned."
  (let [index-of-before (.indexOf str before)]
    (if (= -1 index-of-before)
      str
      (.substring str 0 index-of-before))))

(defn- params->map [param-string]
  (if-not (blank? param-string)
    (let [params-splitted-by-ampersand (split param-string #"&")
          ; Handle no-value parameters. If a no-value parameter is found then use nil as parameter value
          param-list (mapcat #(let [index-of-= (.indexOf % "=")]
                               (if (and
                                     (> index-of-= 0)
                                     ; Check if the first = char is the last char of the param.
                                     (< (inc index-of-=) (.length %)))
                                 (split % #"=")
                                 [% nil])) params-splitted-by-ampersand)]
      (keywordize-keys (apply hash-map param-list)))))

(defn- create-nano-response [{:keys [status headers body content-type]}]
  "Create a nano-httpd Response from the given map.

   path - The request path to mock, for example /search
   status - The status code
   headers - The response headers (list or vector of tuples specifying headers). For example ([\"Content-Type\" \"application/json\"], ...)
   body - The response body"
  (let [; We see if a predefined status exists for the supplied status code
        istatus (first (filter #(= (.getRequestStatus %) status) (NanoHTTPD$Response$Status/values)))
        ; If no match then we create a custom implementation of IStatus with the supplied status
        istatus (or istatus (reify NanoHTTPD$Response$IStatus
                              (getDescription [_] "")
                              (getRequestStatus [_]
                                status)))

        nano-response (NanoHTTPD/newFixedLengthResponse istatus content-type body)]
    (for [[name value] headers]
      (.addHeader nano-response name value))
    nano-response))

(defn- indices-of-route-matching-stub-request [stub-http-request routes]
  (keep-indexed #(if (true? ((:request-spec-fn %2) stub-http-request)) %1 nil) routes))

(defn- http-session->stub-http-request
  "Converts an instance of IHTTPSession to a \"stub-http\" representation of a request"
  [^NanoHTTPD$IHTTPSession session]
  (let [body-map (HashMap.)
        _ (.parseBody session body-map)
        ; Convert java hashmap into a clojure map
        body-map (into {} body-map)
        path (.getUri session)
        method (->> session .getMethod .toString)
        headers (->> (.getHeaders session)
                     (map-kv (comp keyword lower-case) identity))
        req {:method       method
             :headers      headers
             :content-type (:content-type :headers)
             :path         path
             ; There's no way to get the protocol version now? File an issue!
             :request-line (str method " " path " HTTP/1.1")
             :body         (if (empty? body-map) nil body-map)
             :query-params (params->map (.getQueryParameterString session))}
        req-no-nils (into {} (filter (comp some? val) req))]
    req-no-nils))

(defn- new-nano-server! [port routes]
  "Create a nano server instance that will return the same response over and over on match"
  (proxy [NanoHTTPD] [port]
    (serve [^NanoHTTPD$IHTTPSession session]
      (let [current-routes @routes
            stub-request (http-session->stub-http-request session)
            indicies-matching-request (indices-of-route-matching-stub-request stub-request current-routes)
            matching-route-count (count indicies-matching-request)]
        (cond
          ; TODO Make this configurable by allowing to determine what should happen by supplying a :default response
          (> matching-route-count 1)
          (throw (ex-info
                   (str "Failed to determine response since several routes matched request: " stub-request ". Matching response specs are:\n")
                   {:matching-specs (map #(select-keys % [:request-spec :response-spec]) ; Select only the request spec and response spec
                                         ; Get the routes for the matching indicies
                                         (mapv current-routes indicies-matching-request))}))
          (= matching-route-count 0) (throw (ex-info (str "Failed to determine response since no route matched request: " stub-request ". Routes are:\n")
                                                     {:routes current-routes})))
        (let [index-matching (first indicies-matching-request)
              response-fn (:response-spec-fn (get current-routes index-matching))
              stub-response (response-fn stub-request)
              nano-response (create-nano-response stub-response)]
          ; Record request
          (swap! routes update-in [index-matching :recordings] (fn [invocations]
                                                                 (conj invocations
                                                                       {:request  stub-request
                                                                        :response stub-response})))
          nano-response)))))


(defn- request-spec-matches? [request-spec request]
  (letfn [(path-without-query-params [path]
            (substring-before path "?"))
          (method-matches? [expected-method actual-method]
            (let [; Make keyword out of string and never mind "case" of keyword (i.e. :GET and :get are treated the same)
                  normalize #(->> (if (string? %) % (name %)) .toLowerCase keyword)
                  expected-normalized (normalize (or expected-method actual-method)) ; Assume same as actual if not present
                  actual-normalized (normalize actual-method)]
              (= expected-normalized actual-normalized)))
          (path-matches? [expected-path actual-path]
            (= (or expected-path actual-path) actual-path))
          (query-param-matches? [expected-params actual-params]
            (let [expected-params (or expected-params {})   ; Assume empty map if nil
                  query-params-to-match (select-keys actual-params (keys expected-params))]
              (= expected-params query-params-to-match)))]
    (and (apply path-matches? (map (comp path-without-query-params :path) [request-spec request]))
         (query-param-matches? (:query-params request-spec) (:query-params request))
         (method-matches? (:method request-spec) (:method request)))))

(defn- throw-normalization-exception! [type val]
  (let [class-name (-> val .getClass .getName)
        error-message (str "Internal error: Couldn't find " type " conversion strategy for class " (-> val .getClass .getName))]
    (throw (ex-info error-message {:class class-name :value val}))))

(defmulti normalize-request-spec class)
(defmethod normalize-request-spec IFn [req-fn] req-fn)
(defmethod normalize-request-spec IPersistentMap [req-spec] (fn [request] (request-spec-matches? req-spec request)))
(defmethod normalize-request-spec PersistentArrayMap [req-spec] (fn [request] (request-spec-matches? req-spec request)))
(defmethod normalize-request-spec String [path] (normalize-request-spec {:path path}))
(defmethod normalize-request-spec :default [value] (throw-normalization-exception! "request" value))

(defmulti normalize-response-spec class)
(defmethod normalize-response-spec IFn [resp-fn] resp-fn)
(defmethod normalize-response-spec IPersistentMap [resp-map] (fn [_] resp-map))
(defmethod normalize-response-spec PersistentArrayMap [resp-map] (fn [_] resp-map))
(defmethod normalize-response-spec String [body] (fn [_] {:status 200 :content-type "text/plain" :headers {:content-type "text/plain"} :body body}))
(defmethod normalize-response-spec :default [value] (throw-normalization-exception! "response" value))

(defn- record-route! [route-state route-matcher response-spec]
  (swap! route-state conj {:request-spec-fn  (normalize-request-spec route-matcher) :request-spec route-matcher
                           :response-spec-fn (normalize-response-spec response-spec) :response-spec response-spec
                           :recordings       []}))

(defn- get-free-port! []
  (let [socket (ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defprotocol Response
  (recorded-requests [this] "Return all recorded requests")
  (recorded-responses [this] "Return all recorded responses"))

(defrecord NanoFakeServer [nano-server routes]
  Closeable
  (close [_]
    (.stop nano-server))
  Response
  (recorded-requests [_]
    (mapcat #(map :request (:recordings %)) @routes))
  (recorded-responses [_]
    (mapcat #(map :response (:recordings %)) @routes)))

(defn start!
  "Start a new fake web server on a random free port. Usage example:

  (with-open [server (start!
         {\"something\" {:status 200 :content-type \"application/json\" :body (json/generate-string {:hello \"world\"})}
         {:path \"/y\" :query-params {:q \"something\")}} {:status 200 :content-type \"application/json\" :body  (json/generate-string {:hello \"brave new world\"})}})]
         ; Do actual HTTP request
         )"
  ([routes] (start! {} routes))
  ([settings routes]
   {:pre [(map? settings) (or (map? routes) (function? routes))]}
   (let [{:keys [port] :or {port (get-free-port!)}} settings
         route-state (atom [])
         nano-server (new-nano-server! port route-state)
         _ (.start nano-server)
         uri (str "http://localhost:" (.getListeningPort nano-server))
         server (map->NanoFakeServer {:uri uri :port port :nano-server nano-server :routes route-state})
         routes-map (if (map? routes)
                      routes
                      (routes server))
         _ (assert (map? routes-map))]
     (doseq [[req-spec resp-spec] routes-map]
       (record-route! route-state req-spec resp-spec))
     server)))

(defmacro with-routes!
  "Applies routes and creates and stops a fake server implicitly"
  {:arglists '([bindings? routes & body])}
  [bindings-or-routes & more-args]
  (let [[bindings routes body] (if (vector? bindings-or-routes)
                                 [bindings-or-routes (first more-args) (rest more-args)]
                                 [[] bindings-or-routes more-args])]

    (assert (and (vector? bindings)
                 (even? (count bindings))
                 (every? symbol? (take-nth 2 bindings))) "Bindings need to be a vector with an even number of forms")

    `(let ~bindings
       (let [server# (start! ~routes)
             ~'uri (:uri server#)
             ~'server server#]
         (try
           ~@body
           (finally
             (.close server#)))))))