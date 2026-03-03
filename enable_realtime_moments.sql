-- Enable Realtime for the moments and moment_views tables
-- This is necessary for changes in these tables to be broadcasted via WebSockets

ALTER PUBLICATION supabase_realtime ADD TABLE public.moments;
ALTER PUBLICATION supabase_realtime ADD TABLE public.moment_views;
