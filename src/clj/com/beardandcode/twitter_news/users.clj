(ns com.beardandcode.twitter-news.users
  (:require [clojure.core.async :as async]
            [cheshire.core :as json]
            [com.stuartsierra.component :as component]
            [metrics.core :refer [new-registry]]
            [metrics.gauges :refer [gauge-fn]]
            [com.beardandcode.twitter-news.api :as api]
            [com.beardandcode.twitter-news.stats :as stats]))

(defn- find-users-from [client screen-name]
  (let [tweets (api/tweets client screen-name)
        favourites (api/favourites client screen-name)
        only-retweets (async/chan 200 (comp (filter #(get % "retweeted"))
                                            (map #(get % "retweeted_status"))))
        filtered-tweets (async/merge [favourites only-retweets])
        screen-names (async/chan 200 (comp (filter #(-> % (get-in ["entities" "urls"]) empty? not))
                                           (map #(get-in % ["user" "screen_name"]))))]
    (async/pipe tweets only-retweets)
    (async/pipe filtered-tweets screen-names)
    (async/go
      (let [frequencies (loop [frequencies {}]
                          (if-let [screen-name (async/<! screen-names)]
                            (recur (update frequencies screen-name (fnil inc 0)))
                            frequencies))]
        (->> frequencies
             (sort (fn [[_ a] [_ b]] (compare b a)))
             (map first))))))

(defrecord TrustedUsers [seed client metrics-registry users-chan]
  component/Lifecycle
  (start [accounts]
    (let [metrics-registry (new-registry)
          users-chan (async/chan (stats/buffer "to-process" 100 metrics-registry))
          users-followed (atom #{})]
      (gauge-fn metrics-registry "followed" #(count @users-followed))
      (async/onto-chan users-chan seed false)
      (async/go-loop []
        (if-let [screen-name (async/<! users-chan)]
          (if (not (@users-followed screen-name))
            (let [trusted-users (async/<! (find-users-from client screen-name))]
              (swap! users-followed conj screen-name)
              (async/onto-chan users-chan (take 10 trusted-users) false)
              (recur))
            (recur))))
      (assoc accounts :metrics-registry metrics-registry :users-chan users-chan)))
  (stop [accounts]
    (async/close! users-chan)
    (dissoc accounts :metrics-registry :users-chan))

  stats/StatsProvider
  (stats [_] (stats/from-registry metrics-registry)))

(defn new-users [seed-list]
  (map->TrustedUsers {:seed seed-list}))
