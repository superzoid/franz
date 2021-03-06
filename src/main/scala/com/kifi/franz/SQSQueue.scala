package com.kifi.franz

import play.api.libs.json.JsValue
import play.api.libs.iteratee.Enumerator

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.collection.JavaConverters._
import scala.language.implicitConversions

import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.model.{
  SendMessageRequest,
  GetQueueUrlRequest,
  ReceiveMessageRequest,
  DeleteMessageRequest,
  SendMessageResult,
  ReceiveMessageResult,
  CreateQueueRequest
}
import com.amazonaws.handlers.AsyncHandler

case class QueueName(name: String)

case class MessageId(id: String)

case class SQSMessage[T](id: MessageId, body: T, consume: () => Unit, attributes: Map[String,String]) {
  def consume[K](block: T => K): K = {
    val returnValue = block(body)
    consume()
    returnValue
  }
}

trait SQSQueue[T]{

  val queue: QueueName

  protected val sqs: AmazonSQSAsync
  protected val createIfNotExists: Boolean
  protected implicit def asString(obj: T): String
  protected implicit def fromString(s: String): T

  protected val queueUrl: String = initQueueUrl()
  protected def initQueueUrl() = {
    if (createIfNotExists){
      sqs.createQueue(new CreateQueueRequest(queue.name)).getQueueUrl
    } else {
      sqs.getQueueUrl(new GetQueueUrlRequest(queue.name)).getQueueUrl
    }
  }


  def send(msg: T): Future[MessageId] = {
    val request = new SendMessageRequest
    request.setMessageBody(msg)
    request.setQueueUrl(queueUrl)
    val p = Promise[MessageId]()
    sqs.sendMessageAsync(request, new AsyncHandler[SendMessageRequest,SendMessageResult]{
      def onError(exception: Exception) = p.failure(exception)
      def onSuccess(req: SendMessageRequest, res: SendMessageResult) = p.success(MessageId(res.getMessageId))
    })
    p.future
  }

  protected def nextBatchRequestWithLock(requestMaxBatchSize: Int, lockTimeout: FiniteDuration): Future[Seq[SQSMessage[T]]] = {
    val request = new ReceiveMessageRequest
    request.setMaxNumberOfMessages(requestMaxBatchSize)
    request.setVisibilityTimeout(lockTimeout.toSeconds.toInt)
    request.setWaitTimeSeconds(10)
    request.setQueueUrl(queueUrl)
    val p = Promise[Seq[SQSMessage[T]]]()
    sqs.receiveMessageAsync(request, new AsyncHandler[ReceiveMessageRequest, ReceiveMessageResult]{
      def onError(exception: Exception) = p.failure(exception)
      def onSuccess(req: ReceiveMessageRequest, response: ReceiveMessageResult) = {
        try {
          val rawMessages = response.getMessages
          p.success(rawMessages.asScala.map { rawMessage =>
            SQSMessage[T](
              id = MessageId(rawMessage.getMessageId),
              body = rawMessage.getBody,
              consume = {() =>
                val request = new DeleteMessageRequest
                request.setQueueUrl(queueUrl)
                request.setReceiptHandle(rawMessage.getReceiptHandle)
                sqs.deleteMessageAsync(request)
              },
              attributes = rawMessage.getAttributes.asScala.toMap)
          })
          } catch {
            case t: Throwable => p.failure(t)
          }
      }
    })
    p.future
  }


  def nextBatchWithLock(maxBatchSize: Int, lockTimeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Seq[SQSMessage[T]]] = {
    val maxBatchSizePerRequest = 10
    val requiredBatchRequests = Seq.fill(maxBatchSize / maxBatchSizePerRequest)(maxBatchSizePerRequest) :+ (maxBatchSize % maxBatchSizePerRequest)
    val futureBatches = requiredBatchRequests.collect {
      case requestMaxBatchSize if requestMaxBatchSize > 0 => nextBatchRequestWithLock(requestMaxBatchSize, lockTimeout)
    }
    Future.sequence(futureBatches).map { batches =>
      val messages =  batches.flatten
      val distinctMessages = messages.map { message => message.id -> message }.toMap.values
      distinctMessages.toSeq
    }
  }

  def next(implicit ec: ExecutionContext): Future[Option[SQSMessage[T]]] = nextBatchRequestWithLock(1, new FiniteDuration(0, SECONDS)).map(_.headOption)
  def nextWithLock(lockTimeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Option[SQSMessage[T]]] = nextBatchRequestWithLock(1, lockTimeout).map(_.headOption)
  def nextBatch(maxBatchSize: Int)(implicit ec: ExecutionContext): Future[Seq[SQSMessage[T]]] = nextBatchWithLock(maxBatchSize, new FiniteDuration(0, SECONDS))
  def enumerator(implicit ec: ExecutionContext): Enumerator[SQSMessage[T]] = Enumerator.repeatM[SQSMessage[T]]{ loopFuture(next) }
  def enumeratorWithLock(lockTimeout: FiniteDuration)(implicit ec: ExecutionContext): Enumerator[SQSMessage[T]] = Enumerator.repeatM[SQSMessage[T]]{ loopFuture(nextWithLock(lockTimeout)) }

  private def loopFuture[A](f: => Future[Option[A]], promise: Promise[A] = Promise[A]())(implicit ec: ExecutionContext): Future[A] = {
    f.onComplete {
      case util.Success(Some(res)) => promise.success(res)
      case util.Success(None) => loopFuture(f, promise)
      case util.Failure(ex) => promise.failure(ex)
    }
    promise.future
  }

}


