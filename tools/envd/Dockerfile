FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY *.go ./
RUN go test ./... \
    && go vet ./... \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/mika-envd .

FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl git jq \
    && addgroup -g 10001 mika \
    && adduser -D -u 10002 -G mika -H runner \
    && mkdir -p /data \
    && chown 0:10001 /data \
    && chmod 02770 /data
COPY --from=build /out/mika-envd /usr/local/bin/mika-envd
COPY LICENSE NOTICE /licenses/
# envd runs as root inside the container so it can read the root-only token
# secret and drop commands to the runner uid; CAP_SETUID/SETGID are added in
# compose, everything else is dropped.
USER 0
VOLUME ["/data"]
EXPOSE 8787
ENTRYPOINT ["/usr/local/bin/mika-envd"]
CMD ["-listen", "0.0.0.0:8787", "-data", "/data", "-token-env", "ENVD_TOKEN", "-exec-user", "10002", "-exec-group", "10001"]
