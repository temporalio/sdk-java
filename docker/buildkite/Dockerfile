FROM openjdk:8

# Apache Thrift version
ENV APACHE_THRIFT_VERSION=0.9.3

# Install dependencies using apt-get
RUN apt-get update && apt-get add --virtual wget ca-certificates wget && apt-get add --virtual build-dependencies build-base gcc && apt-get add protobuf

# Compile source
RUN set -ex ;\
	wget http://www-us.apache.org/dist/thrift/${APACHE_THRIFT_VERSION}/thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	tar -xvf thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	rm thrift-${APACHE_THRIFT_VERSION}.tar.gz ;\
	cd thrift-${APACHE_THRIFT_VERSION}/ ;\
	./configure --enable-libs=no --enable-tests=no --enable-tutorial=no --with-cpp=no --with-c_glib=no --with-java=yes --with-ruby=no --with-erlang=no --with-go=no --with-nodejs=no --with-python=no ;\
	make -j2 && make install ;\
	cd .. && rm -rf thrift-${APACHE_THRIFT_VERSION}

# Cleanup packages and remove cache
RUN apt-get del build-dependencies wget
#&& rm -rf /var/cache/apk/*

RUN mkdir /temporal-java-client
WORKDIR /temporal-java-client