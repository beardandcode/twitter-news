(ns com.beardandcode.twitter-news.api
  (:import [org.apache.http.client HttpClient]
           [org.apache.http.client.methods HttpGet]
           [org.apache.http.impl.client HttpClients]
           [org.apache.http.util EntityUtils]
           [com.twitter.hbc.httpclient.auth OAuth1])
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [com.stuartsierra.component :as component]
            [metrics.core :refer [new-registry]]
            [metrics.meters :refer [mark! meter]]
            [metrics.timers :refer [timer] :as timers]
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

;; from the twitter api documentation
(def all-statuses [200 304 400 401 403 404 406 410 420 422 429 500 502 503 504])

(defn- execute->json [request status-meters]
  (async/go
    (log/infof "Requesting from %s" (.toString request))
    (let [response (.execute (HttpClients/createDefault) request)
          status-code (.getStatusCode (.getStatusLine response))
          body-string (EntityUtils/toString (.getEntity response) "UTF-8")]
      (.close response)
      (if-let [meter (status-meters status-code)] (mark! meter))
      (cond
        (= status-code 200)
        (json/parse-string body-string)
        
        (= status-code 429)
        (let [limit-cleared-ms (-> response
                                   (.getHeaders "X-Rate-Limit-Reset")
                                   first (.getValue) Long/parseLong (* 1000))
              until-cleared-ms (- limit-cleared-ms (System/currentTimeMillis))]
          (log/infof "Rate was limited, clearing at %d so we are waiting for %dms"
                     limit-cleared-ms until-cleared-ms)
          (if (> until-cleared-ms 0) (async/<! (async/timeout until-cleared-ms)))
          (async/<! (execute->json request status-meters)))

        (> status-code 500)
        (do (async/<! (async/timeout 1000))
            (async/<! (execute->json request status-meters)))
        
        :else
        (log/warnf "Response returned %d: %s" status-code body-string)))))

(defn- dispatch-requests [request-chan request-fn auth name metrics-registry]
  (let [control-chan (async/chan)
        request-rate (meter metrics-registry (str name ".rate"))
        response-timer (timer metrics-registry (str name ".response-time"))
        status-meters (reduce #(assoc %1 %2 (meter metrics-registry (str name ".status." %2)))
                              {} all-statuses)]
    (log/infof "Dispatch[%s] starting" name)
    (async/go-loop []
      (let [[v ch] (async/alts! [request-chan control-chan])]
        (if (= ch request-chan)
          (let [{:keys [params out-chan]} v
                timing (timers/start response-timer)
                response (async/<! (execute->json (request-fn auth params) status-meters))]
            (timers/stop timing)
            (async/>! out-chan response)
            (async/close! out-chan)
            (mark! request-rate)
            (recur))
          (log/infof "Dispatch[%s] stopping" name))))
    control-chan))

(defn- throttled-requester [name metrics-registry auth request-fn num per-period]
  (let [request-chan (async/chan (stats/buffer (str name ".waiting") 100 metrics-registry))
        throttled-chan (throttle-chan request-chan num per-period)
        control-chan (dispatch-requests throttled-chan request-fn auth name metrics-registry)]
    (fn ([] (async/>!! control-chan :kill))
       ([params]
        (let [out-chan (async/chan)]
          (async/go (async/>! request-chan {:params params :out-chan out-chan}))
          out-chan)))))

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
        (do (tweets-requester)
            (favourites-requester)
            (dissoc client :metrics-registry :tweets-requester :favourites-requester))))

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
