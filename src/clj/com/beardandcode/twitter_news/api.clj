(ns com.beardandcode.twitter-news.api
  (:import [org.apache.http.client HttpClient]
           [org.apache.http.client.methods HttpGet]
           [org.apache.http.impl.client HttpClients]
           [org.apache.http.util EntityUtils]
           [com.twitter.hbc.httpclient.auth OAuth1])
  (:require [clojure.core.async :as async]
            [cheshire.core :as json]
            [com.stuartsierra.component :as component]
            [metrics.core :refer [new-registry]]
            [metrics.meters :refer [mark! meter]]
            [throttler.core :refer [throttle-chan]]
            [com.beardandcode.twitter-news.stats :as stats]))


(defn make-auth [consumer-key consumer-secret token secret]
  (OAuth1. consumer-key consumer-secret token secret))


(defn- get-request [base path]
  (fn [auth parameters]
    (let [url (format "%s%s?%s" base path (->> parameters
                                               (map (fn [[k v]] (str k "=" v)))
                                               (clojure.string/join "&")))
          request (HttpGet. url)]
      (.signRequest auth request nil)
      request)))

(defn- execute->json [request]
  (let [response (.execute (HttpClients/createDefault) request)
        body-string (EntityUtils/toString (.getEntity response) "UTF-8")]
    (.close response)
    (json/parse-string body-string)))

(defn- throttled-requester [name metrics-registry auth request-fn num per-period]
  (let [request-chan (async/chan (stats/buffer (str name ".waiting") 100 metrics-registry))
        throttled-chan (throttle-chan request-chan num per-period)
        request-rate (meter metrics-registry (str name ".rate"))]
    (async/go-loop []
      (let [{:keys [params out-chan]} (async/<! throttled-chan)]
        (async/>! out-chan (execute->json (request-fn auth params)))
        (async/close! out-chan)
        (mark! request-rate)
        (recur)))
    (fn [params]
      (let [out-chan (async/chan)]
        (async/go (async/>! request-chan {:params params :out-chan out-chan}))
        out-chan))))

(defn- all-tweets-from [requester user]
  (let [out-chan (async/chan)]
    (async/go-loop [response (async/<! (requester {"screen_name" user "count" 200}))]
      (if (> (count response) 0)
        (let [lowest-id (->> response
                             (map #(get % "id_str"))
                             (map #(Long/parseLong %))
                             sort first)]
          (doseq [tweet response] (async/>! out-chan tweet))
          (recur (async/<! (requester {"screen_name" user "count" 200 "max_id" (dec lowest-id)}))))
        (async/close! out-chan)))
    out-chan))

(defprotocol TwitterClient
  (tweets [_ user] "Returns a stream of a users tweets")
  (favourites [_ user] "Returns a stream of a users favourites"))

(defrecord HttpTwitterClient [base-url auth metrics-registry tweets-requester favourites-requester]
  component/Lifecycle
  (start [client]
    (if tweets-requester client
        (let [metrics-registry (new-registry)]
          (assoc client
                 :metrics-registry metrics-registry
                 :tweets-requester (throttled-requester "tweets" metrics-registry
                                    auth (get-request base-url "/statuses/user_timeline.json") 12 :minute)
                 :favourites-requester (throttled-requester "favourites" metrics-registry
                                        auth (get-request base-url "/favorites/list.json") 1 :minute)))))
  (stop [client]
    (if (not tweets-requester) client
        (dissoc client :metrics-registry :tweets-requester :favourites-requester)))

  TwitterClient
  (tweets [_ user]
    (all-tweets-from tweets-requester user))
  (favourites [_ user]
    (all-tweets-from favourites-requester user))

  stats/StatsProvider
  (stats [_] (stats/from-registry metrics-registry)))

(defn new-client [auth]
  (map->HttpTwitterClient {:base-url "https://api.twitter.com/1.1"
                           :auth auth}))
