FROM clojure:lein-2.8.1-alpine

RUN \
apk --no-cache --update add make dumb-init && \
rm -rf /var/cache/apk/* /tmp && \
mkdir /tmp && \
chmod 777 /tmp

RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app

COPY project.clj /usr/src/app/
COPY ./resources/puppetlabs/puppetdb/bootstrap.cfg /usr/src/app/resources/puppetlabs/puppetdb/bootstrap.cfg
RUN lein deps

COPY . /usr/src/app/
RUN lein with-profiles ezbake,ci,uberjar uberjar
ENTRYPOINT ["/usr/bin/dumb-init","--"]
CMD ["./pdb", "version"]
