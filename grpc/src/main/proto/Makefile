$(VERBOSE).SILENT:
############################# Main targets #############################
# Install everything, run all linters, and compile proto files.
install: grpc-install api-linter-install buf-install proto

# Run all linters and compile proto files.
proto: grpc
########################################################################

##### Variables ######
ifndef GOPATH
GOPATH := $(shell go env GOPATH)
endif

GOBIN := $(if $(shell go env GOBIN),$(shell go env GOBIN),$(GOPATH)/bin)
export PATH := $(GOBIN):$(PATH)

COLOR := "\e[1;36m%s\e[0m\n"

PROTO_ROOT := .
PROTO_FILES = $(shell find $(PROTO_ROOT) -name "*.proto")
PROTO_DIRS = $(sort $(dir $(PROTO_FILES)))
PROTO_OUT := .gen
PROTO_IMPORTS := -I=$(PROTO_ROOT) -I=$(GOPATH)/src/github.com/temporalio/gogo-protobuf/protobuf

$(PROTO_OUT):
	mkdir $(PROTO_OUT)

##### Compile proto files for go #####
grpc: buf api-linter gogo-grpc fix-path

go-grpc: clean $(PROTO_OUT)
	printf $(COLOR) "Compiling for go-gRPC..."
	$(foreach PROTO_DIR,$(PROTO_DIRS),protoc $(PROTO_IMPORTS) --go_out=plugins=grpc,paths=source_relative:$(PROTO_OUT) $(PROTO_DIR)*.proto;)

gogo-grpc: clean $(PROTO_OUT)
	printf $(COLOR) "Compiling for gogo-gRPC..."
	$(foreach PROTO_DIR,$(PROTO_DIRS),protoc $(PROTO_IMPORTS) --gogoslick_out=Mgoogle/protobuf/wrappers.proto=github.com/gogo/protobuf/types,Mgoogle/protobuf/duration.proto=github.com/gogo/protobuf/types,Mgoogle/protobuf/descriptor.proto=github.com/golang/protobuf/protoc-gen-go/descriptor,Mgoogle/protobuf/timestamp.proto=github.com/gogo/protobuf/types,plugins=grpc,paths=source_relative:$(PROTO_OUT) $(PROTO_DIR)*.proto;)

fix-path:
	mv -f $(PROTO_OUT)/temporal/api/* $(PROTO_OUT) && rm -rf $(PROTO_OUT)/temporal

##### Plugins & tools #####
grpc-install: gogo-protobuf-install
	printf $(COLOR) "Installing/updating gRPC plugins..."
	go get -u google.golang.org/grpc

gogo-protobuf-install: go-protobuf-install
	go get -u github.com/temporalio/gogo-protobuf/protoc-gen-gogoslick

go-protobuf-install:
	go get -u github.com/golang/protobuf/protoc-gen-go

api-linter-install:
	printf $(COLOR) "Installing/updating api-linter..."
	go get -u github.com/googleapis/api-linter/cmd/api-linter

buf-install:
	printf $(COLOR) "Installing/updating buf..."
	go get -u github.com/bufbuild/buf/cmd/buf

##### Linters #####
api-linter:
	printf $(COLOR) "Running api-linter..."
	api-linter --set-exit-status --output-format=summary $(PROTO_IMPORTS) --config $(PROTO_ROOT)/api-linter.yaml $(PROTO_FILES)

buf:
	printf $(COLOR) "Running buf linter..."
	(cd $(PROTO_ROOT) && buf check lint)

##### Clean #####
clean:
	printf $(COLOR) "Deleting generated go files..."
	rm -rf $(PROTO_OUT)
