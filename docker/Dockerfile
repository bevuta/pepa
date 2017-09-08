FROM debian:stretch

RUN apt-get update

RUN apt-get -y install apt-utils \
                       openjdk-8-jre-headless \
                       pdftk \
                       postgresql-client \
                       poppler-utils \
                       tesseract-ocr \
                       tesseract-ocr-eng \
                       tesseract-ocr-deu \
                       grep pcregrep \
                       curl git \
                       nano wget

RUN mkdir -p /pepa/build/bin
WORKDIR /pepa/build/bin

RUN curl -O https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein \
        && chmod a+x ./lein \
        && ./lein > /dev/null

RUN git clone https://github.com/bevuta/pepa /pepa/build/src

WORKDIR /pepa/build/src

ARG pepa_commit
ENV PEPA_COMMIT ${pepa_commit:-3540253f8e8fbdb5ea1dffe230a27df624032713}

RUN echo Building Pepa commit $PEPA_COMMIT \
        && git fetch \
        && git checkout $PEPA_COMMIT

RUN ../bin/lein uberjar \
        && mv target/pepa-*-standalone.jar /pepa \
        && rm -rf /root/.m2

EXPOSE 4035 6332

WORKDIR /pepa
RUN rm -rf build

ADD config.clj /pepa
ADD setup.sh /pepa

RUN sed -i 's/\r$//' setup.sh

CMD /bin/bash setup.sh
