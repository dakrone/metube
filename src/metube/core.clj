(ns metube.core
  (:require [robert.bruce :refer [try-try-again *try*]]
            [clojure.java.shell :as sh]
            [immutant.messaging :as msg]))

(def qn "queue.notifications")
(def youtube-dl-cmd "youtube-dl")

(defonce youtube-dl-enabled?
  (= 0 (:exit (sh/sh "which" "youtube-dl"))))

(defn download
  [url]
  (when youtube-dl-enabled?
    (let [exit (:exit (sh/sh youtube-dl-cmd
                             "-t"
                             url))]
      (when-not (zero? exit)
        (throw (Exception. (str "error downloading " url)))))
    true))

(defn download-youtube-url [url]
  (try
    (let [resp (try-try-again
                :sleep 5000
                :tries 5
                :error-hook
                (fn [e]
                  (msg/publish qn (str "Error downloading: " url
                                       ", trying again. [" *try* "/5] tries")))
                #(download url))]
      (when resp
        (msg/publish qn (str "Successfully downloaded " url))))
    (catch Throwable e
      (msg/publish qn (str "Unable to download: " url ", reason: " e)))))
