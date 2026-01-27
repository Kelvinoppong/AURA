import Shell from "@/components/Shell";
import ChatView from "@/components/ChatView";

export const dynamic = "force-dynamic";

export default function Home() {
  return (
    <Shell>
      <ChatView />
    </Shell>
  );
}
