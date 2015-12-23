(ns com.beardandcode.twitter-news.stats
  (:import [java.util LinkedList])
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [metrics.gauges :refer [gauge-fn value]]
            [metrics.meters :refer [rates]]))

(defprotocol StatsProvider
  (stats [_]))

(defrecord StatsBuffer [queue capacity]
  async-protocols/Buffer
  (full? [_] (println "full?") (>= (.size queue) capacity))
  (remove! [_] (println "remove!") (.remove queue))
  (add!* [_ value] (println "add!*") (.add value))
  (close-buf! [_] (println "close-buf!")))

(defn buffer [name capacity metrics-registry]
  (let [buf (async/buffer capacity)]
    (gauge-fn metrics-registry (format "%s-size" name) #(count buf))
    (gauge-fn metrics-registry (format "%s-remaining" name) #(- capacity (count buf)))
    buf))

(defn- metrics-from [get-fn parse-fn]
  (->> (get-fn)
         (map (fn [entry] {(-> entry .getKey .toString)
                          (-> entry .getValue parse-fn)}))))

(defn from-registry [registry]
  (apply merge (concat (metrics-from #(.getMeters registry) rates)
                       (metrics-from #(.getGauges registry) value))))
