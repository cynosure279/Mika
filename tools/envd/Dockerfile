FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY *.go ./
RUN go test ./... \
    && go vet ./... \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/mika-envd .

FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl git jq \
    && adduser -D -u 10001 -h /data mika \
    && mkdir -p /data \
    && chown 10001:10001 /data
COPY --from=build /out/mika-envd /usr/local/bin/mika-envd
COPY LICENSE NOTICE /licenses/
USER 10001
VOLUME ["/data"]
EXPOSE 8787
ENTRYPOINT ["/usr/local/bin/mika-envd"]
CMD ["-listen", "0.0.0.0:8787", "-data", "/data", "-token-file", "/run/secrets/envd_token"]
