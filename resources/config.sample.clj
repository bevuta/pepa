{:db {:host "<db-host>"
      :user "pepa"
      :dbname "pepa"}
 :web {:port 4035
       :host "localhost"
       :show-traces true
       :log-requests? false
       :default-page-dpi 150
       ;; 30s Long-Polling timeout
       :poll {:timeout 30}}
 :rendering {:png {:dpi #{50 150}}}
 :smtp {:enable true
        :port 2525
        :host "localhost"}
 ;; Those origins' pages will appear in the Inbox
 :inbox {:origins #{"scanner"}}
 :ocr {:enable true
       :cuneiform {:enable true
                   :timeout (* 40 1000)
                   :languages #{"eng" "ger"}}
       :tesseract {:enable true
                   :timeout (* 40 1000)
                   :languages #{"eng"}}}
 :tagging {
           ;; If true, add origin (web, email, etc.) as tagq
           :add-origin? true
           ;; If enabled, add the 'from' and/or 'to' address as tag on
           ;; mailed documents
           :mail/to true
           :mail/from false
           ;; List of tags to add to new documents. Useful to mark
           ;; uploaded/emailed documents for further review
           :new-document ["new"]}}
