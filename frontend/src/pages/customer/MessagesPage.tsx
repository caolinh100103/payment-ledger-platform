import { useNotifications, type Notification } from '../../api/queries'
import { Empty, ErrorNotice, Loading, PageHeader } from '../../components/ui'
import { formatDateTime } from '../../lib/format'

/** The balance-change SMS sent after every movement, as Vietnamese banks send them. */
export function MessagesPage() {
  const messages = useNotifications()
  return (
    <>
      <PageHeader title="Messages" subtitle="The SMS we send you after every movement on your accounts." />
      <MessageList query={messages} />
    </>
  )
}

export function MessageList({ query, limit }: { query: ReturnType<typeof useNotifications>; limit?: number }) {
  if (query.isPending) {
    return <Loading />
  }
  if (query.isError) {
    return <ErrorNotice error={query.error} onRetry={() => void query.refetch()} />
  }
  const messages = limit ? query.data.slice(0, limit) : query.data
  if (messages.length === 0) {
    return <Empty title="No messages yet.">They arrive a moment after money moves.</Empty>
  }
  return (
    <ol className="messages">
      {messages.map((message: Notification) => (
        <li key={message.id} className="message">
          <div className="message__meta">
            <span className="badge badge--info">{message.channel}</span>
            <time dateTime={message.createdAt}>{formatDateTime(message.createdAt)}</time>
          </div>
          <p className="message__text">{message.message}</p>
        </li>
      ))}
    </ol>
  )
}
