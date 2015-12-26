(ns com.beardandcode.twitter-news.stats
  (:import [java.util LinkedList])
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [metrics.gauges :refer [gauge-fn value]]
            [metrics.meters :refer [mark! meter rates]]
            [metrics.timers :refer [percentiles]]))

(defprotocol StatsProvider
  (stats [_]))

(deftype StatsBuffer [queue capacity in-meter out-meter]
  async-protocols/Buffer
  (full? [_] (>= (.size queue) capacity))
  (remove! [_] (mark! out-meter) (.removeLast queue))
  (add!* [this value] (.addFirst queue value) (mark! in-meter) this)
  (close-buf! [_])

  clojure.lang.Counted
  (count [_] (.size queue)))

(defn buffer [name capacity metrics-registry]
  (let [queue (LinkedList.)
        buf (StatsBuffer. queue capacity
                          (meter metrics-registry (format "%s.in" name))
                          (meter metrics-registry (format "%s.out" name)))]
    (gauge-fn metrics-registry (format "%s.size" name) #(.size queue))
    (gauge-fn metrics-registry (format "%s.remaining" name) #(- capacity (.size queue)))
    buf))

(defn- metrics-from [get-fn parse-fn]
  (->> (get-fn)
         (map (fn [entry] {(-> entry .getKey .toString)
                          (-> entry .getValue parse-fn)}))))

(defn from-registry [registry]
  (reduce (fn [out [key val]]
            (assoc-in out (clojure.string/split key (re-pattern "\\.")) val))
          {}
          (apply merge (concat (metrics-from #(.getMeters registry) rates)
                               (metrics-from #(.getGauges registry) value)
                               (metrics-from #(.getTimers registry) percentiles)))))
