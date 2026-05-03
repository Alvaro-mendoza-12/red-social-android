-- ==========================================
-- DIAGNÓSTICO: Ver todas las políticas actuales
-- ==========================================

-- Ver TODAS las políticas de community_members
SELECT 
    '=== COMMUNITY_MEMBERS ===' as seccion,
    policyname,
    cmd as operacion,
    LEFT(qual::text, 100) as condicion
FROM pg_policies
WHERE tablename = 'community_members' 
AND schemaname = 'public'
ORDER BY cmd;

-- Ver TODAS las políticas de group_members
SELECT 
    '=== GROUP_MEMBERS ===' as seccion,
    policyname,
    cmd as operacion,
    LEFT(qual::text, 100) as condicion
FROM pg_policies
WHERE tablename = 'group_members' 
AND schemaname = 'public'
ORDER BY cmd;

-- Ver TODAS las políticas de community_messages
SELECT 
    '=== COMMUNITY_MESSAGES ===' as seccion,
    policyname,
    cmd as operacion,
    LEFT(qual::text, 100) as condicion
FROM pg_policies
WHERE tablename = 'community_messages' 
AND schemaname = 'public'
ORDER BY cmd;

-- Ver TODAS las políticas de group_messages
SELECT 
    '=== GROUP_MESSAGES ===' as seccion,
    policyname,
    cmd as operacion,
    LEFT(qual::text, 100) as condicion
FROM pg_policies
WHERE tablename = 'group_messages' 
AND schemaname = 'public'
ORDER BY cmd;

-- Ver las funciones helper
SELECT 
    '=== FUNCIONES HELPER ===' as seccion,
    proname as funcion,
    prosecdef as es_security_definer
FROM pg_proc 
WHERE proname IN (
    'is_group_member', 
    'is_group_admin',
    'is_community_member', 
    'is_community_admin'
)
AND pronamespace = 'public'::regnamespace;
