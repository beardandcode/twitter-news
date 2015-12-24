(ns com.beardandcode.twitter-news.processor
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [metrics.core :refer [new-registry]]
            [metrics.meters :refer [mark! meter]]
            [com.beardandcode.twitter-news.api.streaming :as s]
            [com.beardandcode.twitter-news.stats :as stats]))

(defrecord FunctionProcessor [process-fn streamer in-chan metric-registry]
  component/Lifecycle
  (start [processor]
    (if in-chan processor
        (let [metric-registry (new-registry)
              in-meter (meter metric-registry "inbound")
              in-chan (async/chan 1 (map #(do (mark! in-meter) %)))]
          (s/tap streamer in-chan)
          (process-fn in-chan metric-registry)
          (assoc processor :in-chan in-chan :metric-registry metric-registry))))
  (stop [processor]
    (if (not in-chan) processor
        (do (s/untap streamer in-chan)
            (async/close! in-chan)
            (dissoc processor :in-chan))))

  stats/StatsProvider
  (stats [_]
    (stats/from-registry metric-registry)))

(defn new-processor [process-fn]
  (map->FunctionProcessor {:process-fn process-fn}))
