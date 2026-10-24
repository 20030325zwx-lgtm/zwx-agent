#!/bin/sh
set -eu

cd "$(dirname "$0")"
if [ ! -f .env ]; then
  echo "Missing .env; copy .env.example and set local credentials." >&2
  exit 2
fi
set -a
. ./.env
set +a

base="http://127.0.0.1:${FLOWABLE_UI_PORT:-8181}/flowable-ui/process-api"
auth="${FLOWABLE_ADMIN_USER:-admin}:${FLOWABLE_ADMIN_PASSWORD:?Set FLOWABLE_ADMIN_PASSWORD in .env}"

usage() {
  echo "Usage: $0 deploy | start <documentId> <tenantId> [reviewer] | tasks <processInstanceId> | complete <taskId> <true|false> [reviewNote]" >&2
  exit 2
}

command=${1:-}
case "$command" in
  deploy)
    curl -fsS -u "$auth" \
      -F "file=@document-review.bpmn20.xml" \
      -F 'deploymentName=Aster document review pilot' \
      "$base/repository/deployments" | jq .
    ;;
  start)
    [ "$#" -ge 3 ] && [ "$#" -le 4 ] || usage
    document_id=$2
    tenant_id=$3
    reviewer=${4:-${FLOWABLE_ADMIN_USER:-admin}}
    payload=$(jq -n --arg reviewer "$reviewer" --arg documentId "$document_id" --arg tenantId "$tenant_id" \
      '{processDefinitionKey:"asterDocumentReview",variables:[{name:"reviewer",value:$reviewer},{name:"documentId",value:$documentId},{name:"tenantId",value:$tenantId}]}')
    curl -fsS -u "$auth" -H 'Content-Type: application/json' -d "$payload" "$base/runtime/process-instances" | jq .
    ;;
  tasks)
    [ "$#" -eq 2 ] || usage
    curl -fsS -u "$auth" "$base/runtime/tasks?processInstanceId=$2" | jq .
    ;;
  complete)
    [ "$#" -ge 3 ] && [ "$#" -le 4 ] || usage
    case "$3" in true|false) ;; *) usage ;; esac
    task_id=$2
    approved=$3
    note=${4:-}
    payload=$(jq -n --argjson approved "$approved" --arg reviewNote "$note" \
      '{action:"complete",variables:[{name:"approved",value:$approved},{name:"reviewNote",value:$reviewNote}]}')
    curl -fsS -u "$auth" -H 'Content-Type: application/json' -X POST -d "$payload" "$base/runtime/tasks/$task_id"
    printf 'Task completed.\n'
    ;;
  *) usage ;;
esac
