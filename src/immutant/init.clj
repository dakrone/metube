(ns immutant.init
  (:require [metube.core]
            [immutant.messaging :as msg]
            [immutant.web :as web]))

(def notify-queue "queue.notifications")

;; Start queue and listen for downloads to download
(msg/start "queue.metube")
(msg/start notify-queue)
(msg/listen "queue.metube" metube.core/download-youtube-url)

;; Set up metube HTTP notification handler
(web/start "/"
           metube.core/metube-handler
           :init #(msg/publish notify-queue "Initialized metube HTTP handler"))
