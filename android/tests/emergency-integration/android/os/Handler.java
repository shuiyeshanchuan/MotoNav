package android.os;
import java.util.PriorityQueue;
public final class Handler {
    private static long order;
    private static final class Task implements Comparable<Task>{final long at,id;final Runnable run;Task(long a,Runnable r){at=a;run=r;id=order++;}public int compareTo(Task b){int x=Long.compare(at,b.at);return x!=0?x:Long.compare(id,b.id);}}
    private static final PriorityQueue<Task> queue=new PriorityQueue<>();
    public Handler(Looper looper){}
    public boolean post(Runnable run){queue.add(new Task(SystemClock.now,run));return true;}
    public boolean postDelayed(Runnable run,long delay){queue.add(new Task(SystemClock.now+delay,run));return true;}
    public void removeCallbacks(Runnable run){queue.removeIf(t->t.run==run);}
    public static void advance(long millis){long end=SystemClock.now+millis;int limit=100000;while(!queue.isEmpty()&&queue.peek().at<=end){Task t=queue.remove();SystemClock.now=t.at;t.run.run();if(--limit==0)throw new AssertionError("runaway handler");}SystemClock.now=end;}
}
