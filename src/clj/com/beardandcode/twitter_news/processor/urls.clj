(ns com.beardandcode.twitter-news.processor.urls
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [metrics.meters :refer [mark! meter]]
            [com.beardandcode.twitter-news.api.streaming :as s]
            [com.beardandcode.twitter-news.stats :as stats]))

(defn- flatten-stream
  ([in-chan] (flatten-stream (async/buffer 100)))
  ([in-chan buf]
   (let [out-chan (async/chan buf)]
     (async/go-loop []
       (let [val (async/<! in-chan)]
         (if (nil? val)
           (async/close! out-chan)
           (do (if (or (vector? val) (list? val) (seq? val))
                 (doseq [to-send (flatten val)]
                   (async/>! out-chan to-send))
                 (async/>! out-chan val))
               (recur)))))
     out-chan)))

(defn extract-urls [in-chan metric-registry]
  (let [done-meter (meter metric-registry "done")
        extract-urls-chan (async/chan (stats/buffer "extract-urls" 1000 metric-registry)
                                      (comp (map #(get-in % ["entities" "urls"]))
                                            (filter #(not (empty? %)))))
        flattened-chan (flatten-stream extract-urls-chan
                                       (stats/buffer "flatten" 1000 metric-registry))
        only-expanded-chan (async/chan (stats/buffer "only-expanded" 1000 metric-registry)
                                       (map #(get % "expanded_url")))]
    (async/pipe in-chan extract-urls-chan)
    (async/pipe flattened-chan only-expanded-chan)
    (async/go-loop []
      (if-let [message (async/<! only-expanded-chan)]
        (do (mark! done-meter)
            (recur))))))


