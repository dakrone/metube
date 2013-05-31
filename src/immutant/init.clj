(ns immutant.init
  (:require [metube.core]
            [immutant.messaging :as msg]))

;; Start queue and listen for downloads to download
(msg/start "queue.metube")
(msg/listen "queue.metube" metube.core/download-youtube-url)
