;; ## EXPERIMENTAL
;;
;; This is a partial and mostly untested implementation of
;; Spark Streaming; consider it a work in progress.
;;
(ns sparkling.streaming
  (:refer-clojure :exclude [map time print union count])
  (:require [sparkling.api :as s]
            [sparkling.conf :as conf]
            [sparkling.function :refer [flat-map-function
                                     function
                                     function2
                                     pair-function
                                     void-function]])
  (:import [org.apache.spark.streaming.api.java JavaStreamingContext JavaDStream]
           [org.apache.spark.streaming.kafka KafkaUtils]
           [org.apache.spark.streaming Duration Time]
           (scala Tuple2)))


(defn untuple [^Tuple2 t]
  (let [v (transient [])]
    (conj! v (._1 t))
    (conj! v (._2 t))
    (persistent! v)))

(defn duration [ms]
  (Duration. ms))

(defn time [ms]
  (Time. ms))

(defn streaming-context
  "conf can be a SparkConf or JavaSparkContext"
  [conf batch-duration]
  (JavaStreamingContext. conf (duration batch-duration)))

(defn local-streaming-context [app-name duration]
  (let [conf (-> (conf/spark-conf)
                 (conf/master "local")
                 (conf/app-name app-name))]
    (streaming-context conf duration)))

(defmulti checkpoint (fn [context arg] (class arg)))
(defmethod checkpoint String [streaming-context path] (.checkpoint streaming-context path))
(defmethod checkpoint Long [dstream interval] (.checkpoint dstream (duration interval)))

(defn text-file-stream [context file-path]
  (.textFileStream context file-path))

(defn socket-text-stream [context ip port]
  (.socketTextStream context ip port))

(defn kafka-stream [& {:keys [streaming-context zk-connect group-id topic-map]}]
  (KafkaUtils/createStream streaming-context zk-connect group-id (into {} (for [[k, v] topic-map] [k (Integer. v)]))))

(defn flat-map [dstream f]
  (.flatMap dstream (flat-map-function f)))

(defn map [dstream f]
  (.map dstream (function f)))

(defn reduce-by-key [dstream f]
  "Call reduceByKey on dstream of type JavaDStream or JavaPairDStream"
  (if (instance? JavaDStream dstream)
    ;; JavaDStream doesn't have a .reduceByKey so cast to JavaPairDStream first
    (-> dstream
      (.mapToPair (pair-function identity))
      (.reduceByKey (function2 f))
      (.map (function untuple)))
    ;; if it's already JavaPairDStream, we're good
    (-> dstream
        (.reduceByKey (function2 f))
        (.map (function untuple)))))

(defn map-to-pair [dstream f]
  (.mapToPair dstream (pair-function f)))


;; ## Transformations
;;
(defn transform [dstream f]
  (.transform dstream (function f)))

(defn repartition [dstream num-partitions]
  (.repartition dstream (Integer. num-partitions)))

(defn union [dstream other-stream]
  (.union dstream other-stream))


;; ## Window Operations
;;
(defn window [dstream window-length slide-interval]
  (.window dstream (duration window-length) (duration slide-interval)))

(defn count [dstream]
  (.count dstream))

(defn count-by-window [dstream window-length slide-interval]
  (.countByWindow dstream (duration window-length) (duration slide-interval)))

(defn group-by-key-and-window [dstream window-length slide-interval]
  (-> dstream
      (.mapToPair (pair-function identity))
      (.groupByKeyAndWindow (duration window-length) (duration slide-interval))
      (.map (function untuple))))

(defn reduce-by-window [dstream f f-inv window-length slide-interval]
  (.reduceByWindow dstream (function2 f) (function2 f-inv) (duration window-length) (duration slide-interval)))

(defn reduce-by-key-and-window [dstream f window-length slide-interval]
  (-> dstream
      (.mapToPair (pair-function identity))
      (.reduceByKeyAndWindow (function2 f) (duration window-length) (duration slide-interval))
      (.map (function untuple))))


;; ## Actions
;;
(def print (memfn print))

(defn foreach-rdd [dstream f]
  (.foreachRDD dstream (function2 f)))


;; ## Output
;;
(defn save-as-text-files
  ;;TODO: check whether first param is of type
  ;;DStream or just let an exception be thrown?
  ([dstream prefix suffix]
    (.saveAsTextFiles dstream prefix suffix))
  ([dstream prefix]
   (.saveAsTextFiles dstream prefix)))
