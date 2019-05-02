FROM openjdk:8-jre

ENV APACHE_THRIFT_VERSION=0.9.3

RUN apt-get update && apt-get -y install build-essential && apt-get install -y wget

RUN set -ex ;\
	wget http://www-us.apache.org/dist/thrift/${APACHE_THRIFT_VERSION}/thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	tar -xvf thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	rm thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	cd thrift-${APACHE_THRIFT_VERSION}/ ;\
	./configure --without-python --without-cpp ;\
	make -j2 && make install ;\
	cd .. && rm -rf thrift-${APACHE_THRIFT_VERSION}

RUN mkdir /cadence-java-client
WORKDIR /cadence-java-client