(ns com.beardandcode.twitter-news.system
  (:require [com.stuartsierra.component :as component]
            [com.beardandcode.components
             [web-server :refer [new-web-server]]
             [routes :refer [new-routes new-context-routes]]]
            [com.beardandcode.twitter-news.api :refer [make-auth new-client]]
            [com.beardandcode.twitter-news.api.streaming :refer [new-filter-stream new-recorded-stream]]
            [com.beardandcode.twitter-news.health :as health]
            [com.beardandcode.twitter-news.processor :refer [new-processor]]
            [com.beardandcode.twitter-news.processor.recorder :refer [record-stream]]
            [com.beardandcode.twitter-news.processor.urls :refer [extract-urls]]
            [com.beardandcode.twitter-news.webapp :as webapp]))

(defn new-system [env]
  (let [twitter-auth (make-auth (:twitter-consumer-key env) (:twitter-consumer-secret env)
                                (:twitter-token env) (:twitter-secret env))]
    (component/system-map
     :client (new-client twitter-auth)
     :streamer (new-filter-stream twitter-auth ["twitter" "instagram" "snap"] [])
     :urls (component/using (new-processor extract-urls) [:streamer])
     :health-routes (component/using
                     (new-routes health/routes-fn {:username (:health-username env)
                                                   :password (:health-password env)})
                     [:streamer :urls])
     :webapp-routes (new-routes webapp/routes-fn)
     :routes (component/using (new-context-routes {"/health" :health-routes
                                                   ""        :webapp-routes})
                              [:webapp-routes :health-routes])
     :web (component/using
           (new-web-server (:ip-address env) (Integer/valueOf (:port env)))
           [:routes]))))

(defn new-recorded-system [path]
  (component/system-map
   :streamer (new-recorded-stream path)
   :urls (component/using (new-processor extract-urls) [:streamer])))

(defn new-recorder-system [creds path terms follows]
  (let [twitter-auth (make-auth (:consumer-key creds) (:consumer-secret creds)
                                (:token creds) (:secret creds))]
    (component/system-map
     :streamer (new-filter-stream twitter-auth terms follows)
     :recorder (component/using (new-processor (record-stream path)) [:streamer]))))

(defn new-live-system [creds terms follows]
  (let [twitter-auth (make-auth (:consumer-key creds) (:consumer-secret creds)
                                (:token creds) (:secret creds))]
    (component/system-map
     :streamer (new-filter-stream twitter-auth terms follows)
     :urls (component/using (new-processor extract-urls) [:streamer]))))

