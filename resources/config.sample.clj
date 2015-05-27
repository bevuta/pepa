{:db {:host "<db-host>"
      :user "pepa"
      :dbname "pepa"}
 :web {:port 4035
       :host "localhost"
       :log-requests? false
       :default-page-dpi 150
       ;; 30s Long-Polling timeout
       :poll {:timeout 30}}
 :rendering {:png {:dpi #{50 150}}}
 :smtp {:enable true
        :port 2525
        :host "localhost"}
 ;; Virtual Network Printer
 :printing {:lpd {:enable true
                  :host "0.0.0.0"
                  :port 6332}}
 ;; Zeroconf announces services offered by Pepa in the network
 :zeroconf {:enable false
            ;; Modules to announce via zeroconf
            :modules #{:lpd :web}
            ;; Optional, allows to set the announced IP Address
            :ip-address nil}
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
           ;; If true, add origin (web, email, etc.) as tag
           :origin true
           ;; If enabled, add the 'from' and/or 'to' address as tag on
           ;; mailed documents
           :mail/to true
           :mail/from false
           ;; Whether to add the printer queue name as a tag
           :printing/queue false
           ;; List of tags to add to new documents. Useful to mark
           ;; uploaded/emailed documents for further review
           :new-document ["new"]}}
