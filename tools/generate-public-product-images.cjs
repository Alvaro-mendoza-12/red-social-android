// Reproducible, code-drawn product illustrations. No private conversations/photos.
const fs=require('node:fs'),path=require('node:path');
const sharp=require('../infrastructure/local-fallback/api/node_modules/sharp');
const root=path.resolve(__dirname,'../NoWing-web/public/images');
const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;');
const text=(x,y,s,size=20,color='#edf2fb',weight=400)=>`<text x="${x}" y="${y}" font-size="${size}" fill="${color}" font-weight="${weight}">${esc(s)}</text>`;
const rect=(x,y,w,h,color='#20252f',r=16)=>`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${color}"/>`;
const avatar=(x,y,s,color='#335b88')=>`<circle cx="${x}" cy="${y}" r="22" fill="${color}"/>${text(x-7,y+7,s,19)}`;
const scene=(x,y,w,h)=>`<svg x="${x}" y="${y}" width="${w}" height="${h}" viewBox="0 0 600 360" preserveAspectRatio="xMidYMid slice"><rect width="600" height="360" fill="url(#sky)"/><circle cx="420" cy="100" r="46" fill="#ffe8a9"/><path d="M0 280 135 120 280 280 410 170 600 310V360H0Z" fill="#487b8d"/><path d="M0 320 180 230 350 330 490 240 600 295V360H0Z" fill="#254e68"/><path d="M0 340Q300 280 600 340V360H0Z" fill="#132e4d"/></svg>`;
const bubble=(x,y,w,message,own=false)=>rect(x,y,w,64,own?'#334e7b':'#2b303a',13)+text(x+18,y+28,message,18)+text(x+w-62,y+51,'18:24',12,'#c0cadb');
function frame(title,subtitle,body){return `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800" viewBox="0 0 1200 800"><defs><linearGradient id="sky" x2="0" y2="1"><stop stop-color="#769db9"/><stop offset="1" stop-color="#eacdc0"/></linearGradient></defs><rect width="1200" height="800" fill="#11141d"/><g font-family="Segoe UI,Arial,sans-serif">${text(55,65,'Novvin',34,'#68dfdd',700)}${text(55,114,title,30,'#f4f7fc',700)}${text(55,147,subtitle,18,'#b9c5d5')}${body}${text(55,755,'Novvin · Android y web',17,'#70d9d8',600)}${text(775,755,'Vista ilustrativa · datos de ejemplo',15,'#aeb9ca')}</g></svg>`}
function chat(kind){
 let body=rect(52,184,1096,524,'#1a1e28',22)+rect(52,184,310,524,'#202530',22);
 body+=text(78,229,kind==='comunidades'?'Comunidades':kind==='grupos'?'Grupos':'Mensajes',23,'#f4f7fc',600);
 const labels=kind==='comunidades'?['Fotografía','# Avisos','# Ideas','# Recursos']:['Amigos','Equipo creativo','Grupo de estudio','Familia'];
 labels.forEach((s,i)=>{const y=280+i*84,selected=i===(kind==='grupos'?2:0);body+=(selected?rect(65,y-31,280,66,'#2c3a51',12):'')+avatar(96,y,s[0])+text(132,y-3,s,18)+text(132,y+22,selected?'Una conversación para compartir':'Fotos, mensajes y novedades',13,'#a8b5c7')});
 const title=kind==='comunidades'?'Comunidad de fotografía':kind==='grupos'?'Grupo de estudio':'Amigos';
 body+=avatar(405,222,title[0])+text(444,220,title,22,'#f4f7fc',600)+text(444,245,kind==='comunidades'?'Canal: avisos':'Mensajes y archivos en un mismo lugar',14,'#aab7ca')+text(1088,232,'···',29);
 body+='<path d="M380 264H1120" stroke="#333a47"/>';
 if(kind==='grupos')body+=text(406,310,'Luis',14,'#6ee0dc')+bubble(405,325,419,'Les comparto el material de hoy.')+rect(425,409,280,86,'#2b303a')+text(444,442,'PDF',20,'#8fbfff',700)+text(500,442,'Material de estudio',18)+text(500,471,'Documento adjunto',14,'#acbbcd')+bubble(755,519,341,'Perfecto, gracias. Ya lo reviso.',true);
 else if(kind==='comunidades')body+=text(405,305,'Avisos de la comunidad',19,'#75dedb',600)+text(405,340,'Un espacio para aprender y compartir.',19)+scene(405,364,340,210)+text(770,410,'Comparte tus ideas',22,'#edf2fb',600)+text(770,447,'Fotos, archivos y conversaciones',17,'#bac8dc')+text(770,478,'organizadas por canales.',17,'#bac8dc')+text(770,545,'Avisos · Ideas · Recursos',16,'#79dedd');
 else body+=bubble(405,295,380,'¿Nos vemos esta tarde?')+bubble(682,378,414,'Sí, a las seis. Les paso las fotos.',true)+scene(785,458,290,144);
 body+=rect(393,630,706,51,'#2a303c',22)+text(417,663,'＋',26,'#c8d4e5')+text(463,662,'Escribe un mensaje...',18,'#aab8ca')+text(1056,662,'➤',23,'#8ebaff');return body;
}
function videos(){let b=rect(52,184,1096,524,'#1a1e28',22);for(let i=0;i<3;i++){const x=80+i*335;b+=rect(x,205,305,435,'#2a303d',18)+scene(x+12,218,281,351)+text(x+23,602,['Un paseo al atardecer','Ideas para compartir','Descubre algo nuevo'][i],18,'#f0f4fa',600)+text(x+23,626,'Video de ejemplo',13,'#acbbcf')}return b+text(80,678,'Explora videos, sigue a sus creadores y comparte con tu gente.',21,'#c5d0e2')}
function social(){return rect(52,184,1096,524,'#1a1e28',22)+avatar(104,228,'N')+text(144,225,'Comunidad Novvin',22,'#f4f7fc',600)+text(144,250,'Publicación de ejemplo',14,'#aeb9ca')+text(80,294,'Un lugar para compartir lo que te gusta.',22)+scene(80,320,660,296)+text(80,657,'♡   Comentar   Compartir',19,'#bdd1ee')+rect(774,217,343,204,'#242b39')+text(798,254,'Tu gente, cerca',23,'#70dedb',600)+text(798,294,'Publicaciones y comentarios',17)+text(798,326,'Fotos, videos y momentos',17)+text(798,358,'Perfiles y comunidades',17)+rect(774,442,343,188,'#26344b')+text(798,480,'Conversa y descubre',22,'#ecf3ff',600)+text(798,520,'Continúa desde el móvil',17)+text(798,551,'o desde tu navegador.',17)}
function moments(){let b=rect(52,184,1096,524,'#1a1e28',22);for(let i=0;i<3;i++){const x=92+i*340;b+=rect(x,210,287,451,'#283140',18)+scene(x+10,220,267,429)+avatar(x+39,251,['A','M','N'][i])+text(x+73,258,['Ana','Mar','Novvin'][i],18,'#fff',600)+rect(x+20,570,245,63,'#172234',14)+text(x+37,609,['Lo mejor del día','Comparte un momento','Una historia para contar'][i],17)}return b}
const items=[
 ['chats','Mensajes que siguen contigo','Conversa y comparte fotos, audios y archivos.',chat('chats')],
 ['grupos','Tus grupos, organizados','Comparte ideas y documentos con tus personas.',chat('grupos')],
 ['comunidades','Encuentra tu comunidad','Conversaciones y canales alrededor de tus intereses.',chat('comunidades')],
 ['videos','Algo nuevo por descubrir','Videos y creadores en Novvin.',videos()],
 ['social','Comparte lo que te gusta','Publicaciones, comentarios y conexiones.',social()],
 ['momentos','Momentos que conectan','Fotos y videos para compartir tu día.',moments()],
];
(async()=>{for(const[name,title,subtitle,body]of items){const svg=frame(title,subtitle,body),file=path.join(root,'novvin-'+name);fs.writeFileSync(file+'.svg',svg);await sharp(Buffer.from(svg)).png({compressionLevel:9}).toFile(file+'.png');console.log(name,fs.statSync(file+'.png').size)}})().catch(e=>{console.error(e.message);process.exitCode=1});
